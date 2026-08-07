package ykd.ykd.llm.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import ykd.ykd.exception.BusinessException;
import ykd.ykd.exception.ErrorCode;
import ykd.ykd.llm.tools.*;
import ykd.ykd.memory.MemoryManagerService;
import ykd.ykd.llm.service.LlmService;
import ykd.ykd.skill.model.SkillDefinition;
import ykd.ykd.skill.registry.SkillRegistry;
import ykd.ykd.skill.selector.SkillSelector;
import ykd.ykd.skill.selector.SkillSelectionResult;
import ykd.ykd.skill.session.SkillSessionManager;
import ykd.ykd.skill.session.SkillSessionManager.SkillSession;
import ykd.ykd.skill.tool.SkillToolResolver;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LLM 调用编排层。
 *
 * <h3>处理流程</h3>
 * <ol>
 *   <li>文本预处理（空格消息、图片兜底文案）</li>
 *   <li>提醒拦截 — 正则匹配提醒请求，直接调工具，绕过 LLM 避免幻觉</li>
 *   <li>Skill 匹配 — 关键词命中时加载对应 Skill，限制工具集</li>
 *   <li>加载对话历史，注入系统上下文（文档内容等）</li>
 *   <li>注入 Skill 提示词（如果有）</li>
 *   <li>注册工具集（Skill 工具 or 通用工具（含 RAG 知识库））</li>
 *   <li>调用 LLM，保存对话记忆，触发压缩检查</li>
 * </ol>
 *
 * <h3>工具隔离</h3>
 * 命中 Skill 时 LLM 只能看到该 Skill 声明的工具白名单；
 * 未命中时暴露通用工具（含 RAG 知识库）但不暴露猎聘工具。
 * 猎聘工具只能在 liepin-auto-apply Skill 激活时使用。
 */
@Slf4j
@Service
public class LlmServiceImpl implements LlmService {

    /** Skill 会话保持时长（毫秒），可在 application.yml 通过 llm.skill-ttl-ms 覆盖 */
    @Value("${llm.skill-ttl-ms:600000}")
    private long skillTtlMs;

    private final ReminderInterceptor reminderInterceptor;
    private final HistoryClearInterceptor historyClearInterceptor;
    private final SkillSelector skillSelector;
    private final SkillToolResolver skillToolResolver;
    private final SkillRegistry skillRegistry;
    private final SkillSessionManager skillSessionManager;
    private final MemoryManagerService memoryManagerService;

    private final WebSearchTools webSearchTools;
    private final LinkTools linkTools;
    private final WeatherTools weatherTools;
    private final ImageTools imageTools;
    private final VideoTools videoTools;
    private final VoiceTools voiceTools;
    private final ReminderTools reminderTools;
    private final LocationTools locationTools;
    private final CalculatorTools calculatorTools;
    private final TranslateTools translateTools;
    private final EmailTools emailTools;
    private final DocumentTools documentTools;

    public LlmServiceImpl(ReminderInterceptor reminderInterceptor,
                           HistoryClearInterceptor historyClearInterceptor,
                          SkillSelector skillSelector,
                          SkillToolResolver skillToolResolver,
                          SkillRegistry skillRegistry,
                          SkillSessionManager skillSessionManager,
                          MemoryManagerService memoryManagerService,
                          WebSearchTools webSearchTools,
                          LinkTools linkTools,
                          WeatherTools weatherTools,
                          ImageTools imageTools,
                          VideoTools videoTools,
                          VoiceTools voiceTools,
                          ReminderTools reminderTools,
                          LocationTools locationTools,
                          CalculatorTools calculatorTools,
                          TranslateTools translateTools,
                          EmailTools emailTools,
                          DocumentTools documentTools) {
        this.reminderInterceptor = reminderInterceptor;
        this.historyClearInterceptor = historyClearInterceptor;
        this.skillSelector = skillSelector;
        this.skillToolResolver = skillToolResolver;
        this.skillRegistry = skillRegistry;
        this.skillSessionManager = skillSessionManager;
        this.memoryManagerService = memoryManagerService;
        this.webSearchTools = webSearchTools;
        this.linkTools = linkTools;
        this.weatherTools = weatherTools;
        this.imageTools = imageTools;
        this.videoTools = videoTools;
        this.voiceTools = voiceTools;
        this.reminderTools = reminderTools;
        this.locationTools = locationTools;
        this.calculatorTools = calculatorTools;
        this.translateTools = translateTools;
        this.emailTools = emailTools;
        this.documentTools = documentTools;
    }

    /**
     * 处理用户消息，返回 LLM 回复。
     *
     * @param text      用户文本（可为空，当只有图片时）
     * @param imageUrls 图片 data URI 列表
     * @param client    目标 ChatClient（DeepSeek 或 Agnes）
     * @param userId    微信用户 ID，用于记忆隔离
     * @return LLM 回复文本
     */
    @Override
    public String chat(String text, List<String> imageUrls, ChatClient client, String userId) {
        return chat(text, imageUrls, client, userId, null);
    }

    @Override
    public boolean exitSkill(String userId) {
        SkillSession removed = skillSessionManager.remove(userId);
        skillSessionManager.removePending(userId);
        return removed != null;
    }

    @Override
    public String chat(String text, List<String> imageUrls, ChatClient client, String userId, String systemContext) {
        return chat(text, imageUrls, client, userId, systemContext, true);
    }

    @Override
    public String chat(String text, List<String> imageUrls, ChatClient client, String userId,
                       String systemContext, boolean skillEnabled) {
        long start = System.currentTimeMillis();
        boolean hasImages = imageUrls != null && !imageUrls.isEmpty();

        // 只有图片没有文字时，给模型一个兜底指令
        if ((text == null || text.isBlank()) && hasImages) {
            text = "请描述这些图片";
        }

        // 提醒请求直接拦截，正则提取时间+消息，不走 LLM
        String intercepted = reminderInterceptor.tryIntercept(text, userId);
        if (intercepted != null) return intercepted;
        String clearResult = historyClearInterceptor.tryIntercept(text, userId);
        if (clearResult != null) return clearResult;

        // 手动退出 Skill 指令，必须在 pickSkill 之前拦截（"退出猎聘"会命中猎聘关键词）
        String exitResult = tryExitSkill(text, userId);
        if (exitResult != null) return exitResult;

        // 前缀退出：支持"退出知识库帮我查询杭州天气"这类"退出词 + 后续请求"的组合。
        // 先真正退出技能会话，再用剩余文本走普通对话（默认工具），
        // 避免技能模式锁死工具导致天气等普通功能不可用。
        String exitedSkillName = null;
        String remainder = extractExitPrefixRemainder(text);
        if (remainder != null) {
            SkillSession removed = skillSessionManager.remove(userId);
            skillSessionManager.removePending(userId);
            exitedSkillName = removed != null ? removed.skillName() : null;
            if (remainder.isBlank()) {
                return exitedSkillName != null
                        ? "已退出" + exitedSkillName + "技能模式，回到普通对话。"
                        : "当前没有活跃的技能模式。";
            }
            text = remainder;
            log.info("[LLM] 前缀退出Skill: userId={}, exitedSkill={}, remainder={}",
                    userId, exitedSkillName, abbrev(remainder, 100));
        }

        String finalText = text;
        log.info("[LLM] 请求开始: userId={}, text={}, imageCount={}",
                userId, abbrev(finalText, 100), hasImages ? imageUrls.size() : 0);

        try {
            // 1. 加载当前用户的对话历史
            List<Message> history = memoryManagerService.getHistory(userId);
            // 2. Skill 匹配（图片消息不匹配 Skill，走视觉模型独立处理）
            //    ACTIVATE: 直接激活技能；CONFIRM: 先向用户确认；NONE: 普通对话
            //    skillEnabled=false（如定时提醒）：完全绕过技能路由，不触碰技能会话状态；
            //    前缀退出后的剩余请求同样跳过技能路由，防止"知识库"等关键词重新激活技能。
            boolean bypassSkillRouting = !skillEnabled || hasImages || remainder != null;
            SkillSelectionResult selection = bypassSkillRouting
                    ? SkillSelectionResult.none()
                    : pickSkill(finalText, userId);

            // 3. 构建请求消息列表（复制历史，避免 Skill 提示词被写入长期记忆）
            List<Message> requestMessages = new ArrayList<>(history);

            // 3a. 注入系统上下文（文档内容等），让 LLM 在回复时参考
            if (systemContext != null && !systemContext.isBlank()) {
                requestMessages.add(new SystemMessage(systemContext));
                log.debug("[LLM] 已注入系统上下文: length={}", systemContext.length());
            }
            // 激活或续期 Skill 会话 / 模糊命中时注入确认提示
            SkillDefinition activatedSkill = null;
            if (selection.isActivate()) {
                activatedSkill = selection.skill();
                skillSessionManager.activate(userId, activatedSkill.name());
                requestMessages.add(0, new SystemMessage(buildSkillPrompt(activatedSkill)));
                log.info("[LLM] 启用Skill: userId={}, skill={}, tools={}",
                        userId, activatedSkill.name(), activatedSkill.tools().size());
            } else if (selection.isConfirm()) {
                // 模糊命中：不锁技能工具，让模型先向用户确认意图
                requestMessages.add(0, new SystemMessage(buildSkillConfirmPrompt(selection.skill())));
                log.info("[LLM] Skill 待确认: userId={}, skill={}",
                        userId, selection.skill().name());
            }

            // 4. 组装请求
            ChatClient.ChatClientRequestSpec requestSpec = client.prompt()
                    .messages(requestMessages)
                    .user(userSpec -> buildUserMessage(userSpec, finalText, imageUrls));

            // 5. 注册工具 — Skill 模式 vs 普通模式
            requestSpec.tools(activatedSkill != null
                    ? resolveSkillTools(activatedSkill, userId)
                    : defaultTools());

            // 6. 调用 LLM
            ChatResponse chatResponse = requestSpec.call().chatResponse();
            if (chatResponse == null || chatResponse.getResult() == null
                    || chatResponse.getResult().getOutput() == null
                    || chatResponse.getResult().getOutput().getText() == null) {
                throw new BusinessException(ErrorCode.AI_CALL_FAILED, "模型未返回有效回复");
            }
            String content = chatResponse.getResult().getOutput().getText();
            if (exitedSkillName != null) {
                content = "✅ 已退出" + exitedSkillName + "技能模式。\n\n" + content;
            }
            // 7. 持久化对话记忆。持久化失败不应让回复丢失：记录日志并继续返回给用户，
            //    避免"LLM 已回复但用户收到错误、对话状态不一致"的孤儿问题
            try {
                memoryManagerService.save(userId, finalText, content, hasImages ? "Agnes" : "DeepSeek");
            } catch (Exception e) {
                log.error("[LLM] 对话记忆保存失败: userId={}, error={}", userId, e.getMessage(), e);
            }
            compressIfNeeded(userId, chatResponse);

            // 8. CONFIRM 轮收尾：模型若直接回答了请求（未询问是否使用技能），说明用户意图与
            //    技能无关，清掉 pending，避免用户随后一句"好的"把无关消息误激活为技能模式。
            if (selection.isConfirm()) {
                SkillSession pending = skillSessionManager.getPending(userId, skillTtlMs);
                if (pending != null && !isSkillConfirmAsk(content)) {
                    skillSessionManager.removePending(userId);
                    log.debug("[LLM] 模型未询问技能，清除待确认: userId={}, skill={}",
                            userId, pending.skillName());
                }
            }

            log.info("[LLM] 请求完成: elapsed={}ms, userId={}, reply={}",
                    System.currentTimeMillis() - start, userId, abbrev(content, 200));
            return content;

        } catch (Exception e) {
            log.error("[LLM] 请求异常: elapsed={}ms, userId={}, text={}, error={}",
                    System.currentTimeMillis() - start, userId, abbrev(finalText, 100), e.getMessage(), e);
            throw e;
        }
    }

    // ── 工具解析 ──────────────────────────────────────────────

    /**
     * Skill 模式：只暴露 Skill 声明的工具白名单。
     */
    private Object[] resolveSkillTools(SkillDefinition skill, String userId) {
        ToolCallback[] tools = skillToolResolver.resolve(skill);
        log.info("[LLM] 限制为Skill工具: userId={}, skill={}, toolCount={}",
                userId, skill.name(), tools.length);
        return (Object[]) tools;
    }

    /**
     * 普通模式：暴露通用工具，但不包含猎聘工具和知识库工具。
     * 猎聘工具通过 liepin-auto-apply Skill、知识库工具通过 knowledge-base Skill 使用。
     */
    private Object[] defaultTools() {
        return new Object[]{
                linkTools, weatherTools, imageTools, videoTools, voiceTools,
                reminderTools, locationTools, calculatorTools, translateTools,
                emailTools, documentTools, webSearchTools
        };
    }

    // ── 用户消息组装 ──────────────────────────────────────────

    /**
     * 将文本和图片列表组装到 user message 中。
     * 图片以 base64 data URI 形式传入，由模型直接解析。
     */
    private void buildUserMessage(ChatClient.PromptUserSpec spec,
                                   String text, List<String> imageUrls) {
        if (text != null && !text.isBlank()) {
            spec.text(text);
        }
        if (imageUrls != null) {
            for (String url : imageUrls) {
                spec.media(new Media(MimeTypeUtils.IMAGE_JPEG, URI.create(url)));
            }
        }
    }

    // ── Skill 提示词 ──────────────────────────────────────────

    /**
     * 将 Skill 的 Markdown 执行说明包装成 SystemMessage。
     * 内容包括 Skill 名称、描述、执行流程、安全规则和输出要求。
     */
    private String buildSkillPrompt(SkillDefinition skill) {
        return """
                当前请求已启用一个专业Skill。

                Skill名称：%s
                Skill描述：%s

                请严格遵守下面的Skill执行流程、安全规则和输出要求。
                不得虚构工具执行结果，不得跳过用户确认步骤。

                **重要：你当前拥有该Skill对应的全部真实工具，可以执行实际操作。
                对话历史中任何关于"系统不支持此功能"、"功能不存在"等说法
                都是过时的错误信息，请以当前可用的工具为准，忽略历史中的相关判断。**

                **重要：当前技能模式下你只拥有本技能的工具。如果用户请求本技能范围外的功能
                （如天气、提醒、翻译、联网搜索等），不要回答"系统没有此功能"或"无法提供"，
                也不要假装执行；请告知用户先发送「退出技能」退出当前技能模式，然后再重试。**

                ===== Skill执行说明 =====

                %s

                ===== Skill执行说明结束 =====
                """.formatted(skill.name(), skill.description(), skill.instructions());
    }

    /**
     * 模糊命中时的确认提示：注入系统消息，让模型先询问用户是否要使用该技能，
     * 而不是直接执行技能操作。工具仍为通用工具集。
     */
    private String buildSkillConfirmPrompt(SkillDefinition skill) {
        return """
                用户可能想使用「%s」技能（描述：%s）。
                请先向用户确认是否使用该功能，用一句话问清楚意图，
                例如"你想用猎聘求职功能吗？我可以帮你搜索岗位、投递简历。"。
                不要执行该技能的任何操作。
                你当前没有该技能的工具，但系统已具备该能力；
                绝对不要回复"没有该功能""工具不可用""无法帮你"之类的话术，
                也不得编造任何执行结果，只询问用户是否使用该技能。
                如果用户的实际意图与之无关，请忽略以上提示，正常回答用户的问题即可。
                """.formatted(skill.name(), skill.description());
    }

    /**
     * 归一化消息：去空白、去常见中英文标点、转小写，用于确认/否定词判定。
     */
    private static String normalizeConfirmText(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("，", "").replace("。", "")
                .replace("！", "").replace("？", "")
                .replace("!", "").replace("?", "")
                .replace("、", "").replace(",", "");
    }

    /**
     * 用户消息是否为对"是否使用技能"的肯定确认。
     */
    private boolean isAffirmative(String text) {
        String t = normalizeConfirmText(text);
        if (t.isEmpty()) return false;
        if (t.startsWith("不") || t.startsWith("别") || t.startsWith("没")) return false;
        return t.matches("^(是|对|嗯|好|要|行|可以|确定|确认|用|是的|对的|好的|要的|好呀|可以呀|嗯嗯|ok|yes|确认是|确实)$");
    }

    /** 否定词：消息中任一处出现即不视为技能确认。 */
    private static final List<String> NEGATIVE_TOKENS =
            List.of("不", "别", "没", "不用", "不要", "算了", "取消", "不了");

    /** 肯定词开头：按长度降序匹配，先长后短，避免"好"截断"好嘞"。 */
    private static final List<String> AFFIRMATIVE_PREFIX_TOKENS =
            List.of("可以啊", "好嘞", "好呀", "可以", "确定", "确认", "好的", "嗯嗯",
                    "好", "对", "行", "要", "用", "嗯", "是", "ok", "yes");

    /**
     * 用户消息是否为对"是否使用技能"的肯定确认（放宽版）。
     *
     * <p>判定顺序：空 → 含否定词（否定优先）→ 以肯定词开头 → 整句命中
     * 基础肯定正则 → 具体续句兜底（含数字且含求职相关词，如
     * "投java 杭州 10k左右的工作"）。</p>
     */
    private boolean isAffirmativeFollowUp(String text) {
        String t = normalizeConfirmText(text);
        if (t.isEmpty()) return false;
        if (NEGATIVE_TOKENS.stream().anyMatch(t::contains)) return false;
        for (String token : AFFIRMATIVE_PREFIX_TOKENS) {
            if (t.startsWith(token)) return true;
        }
        if (isAffirmative(text)) return true;
        return t.matches(".*\\d.*")
                && (t.contains("工作") || t.contains("岗位") || t.contains("简历")
                || t.contains("投递") || t.contains("投"));
    }

    /**
     * 用户消息是否为对"是否使用技能"的否定/放弃。
     */
    private boolean isNegative(String text) {
        String t = normalizeConfirmText(text);
        if (t.isEmpty()) return false;
        return t.matches("^(不用|不要|不需要|算了|取消|没有|不是|别|别了|不用了|不要了|不不|不了|不需要了|不用了谢谢)$");
    }

    /**
     * 判断模型回复是否为"是否使用某技能"的确认询问。
     * 仅当回复既带问号又提到技能相关词时，pending 才保留（说明模型确实向用户确认了技能意图）；
     * "已取消提醒。还有其他需要吗？"这类普通收尾语不含技能词，不算确认询问。
     */
    private static boolean isSkillConfirmAsk(String reply) {
        if (reply == null || reply.isBlank()) return false;
        if (!(reply.contains("？") || reply.contains("?"))) return false;
        return reply.contains("技能") || reply.contains("猎聘") || reply.contains("求职")
                || reply.contains("投递") || reply.contains("知识库") || reply.contains("简历");
    }

    // ── 辅助方法 ──────────────────────────────────────────────

    /**
     * 手动退出 Skill：识别退出指令并清除活跃会话，直接返回回复文本，不走 LLM。
     * 必须在 pickSkill 之前调用——"退出猎聘"里的"猎聘"会命中 Skill 关键词。
     */
    private String tryExitSkill(String text, String userId) {
        if (text == null || text.isBlank()) return null;
        String trimmed = text.strip();
        if (trimmed.length() > 10) return null;
        String normalized = trimmed.toLowerCase(Locale.ROOT).replace(" ", "");
        if (isExitCommand(normalized)) {
            SkillSession removed = skillSessionManager.remove(userId);
            skillSessionManager.removePending(userId);
            if (removed != null) {
                log.info("[LLM] 手动退出Skill: userId={}, skill={}", userId, removed.skillName());
                return "已退出" + removed.skillName() + "技能模式，回到普通对话。";
            }
            return "当前没有活跃的技能模式。";
        }
        return null;
    }

    /** 支持"退出技能 + 后续请求"前缀组合的退出词。 */
    private static final List<String> EXIT_PREFIXES = List.of(
            "退出知识库", "退出猎聘", "退出求职", "退出投递", "退出搜索", "退出简历",
            "退出技能", "退出skill",
            "推出知识库", "推出猎聘", "推出求职", "推出投递", "推出搜索", "推出简历",
            "推出技能", "推出skill",
            "关闭知识库", "关闭猎聘", "关闭投递", "关闭求职", "关闭搜索", "关闭技能",
            "结束知识库", "结束猎聘", "结束投递", "结束求职", "结束搜索", "结束技能"
    );

    /**
     * 检测"退出技能 + 后续请求"前缀组合，返回剩余的请求文本。
     *
     * <p>例如 "退出知识库帮我查询杭州天气" → "帮我查询杭州天气"；
     * 纯退出词（无后续内容）返回空字符串；不是退出前缀返回 {@code null}。</p>
     *
     * <p>特意排除"退出投递计划""退出搜索任务"这类计划/任务管理指令——
     * 它们是对猎聘计划/任务的操作，不是退出技能模式。</p>
     */
    private static String extractExitPrefixRemainder(String text) {
        if (text == null) return null;
        String trimmed = text.strip();
        for (String prefix : EXIT_PREFIXES) {
            if (!trimmed.startsWith(prefix)) continue;
            int idx = prefix.length();
            String tail = trimmed.substring(idx);
            // 容忍 "退出知识库技能" 这种技能名后带 "技能/skill" 的写法
            if (tail.startsWith("技能")) {
                idx += 2;
            } else if (tail.toLowerCase(Locale.ROOT).startsWith("skill")) {
                idx += 5;
            }
            String rest = trimmed.substring(idx).stripLeading();
            // 计划/任务管理指令不按退出处理，交给正常技能路由
            if (rest.startsWith("计划") || rest.startsWith("任务")) return null;
            // 容忍 "退出知识库模式帮我查天气" 这类带收尾词的写法
            rest = rest.replaceFirst("^(模式|功能|管理|页面|设置)", "").stripLeading();
            // 去掉前导标点/连接词（"退出知识库，然后查天气"）
            rest = rest.replaceFirst("^[，,。.！!？?、;；:：\\s]+", "");
            return rest;
        }
        return null;
    }

    /**
     * 退出指令匹配（去空格转小写后）。
     * 容忍"推出"（"退出"常见拼音错别字）、"退出简历skill"这类中间词；
     * 允许"退出知识库模式"这类带收尾词的写法；
     * 全程锚定，避免"取消投递""退出投递计划"等计划管理指令被误拦截。
     */
    private static boolean isExitCommand(String normalized) {
        return normalized.matches(
                "^(退出|推出)(skill|技能|猎聘|投递|求职|搜索|简历|知识库)(skill|技能|模式|功能|管理)?$"
                        + "|^(关闭|结束)(skill|技能|猎聘|投递|求职|搜索|知识库)(模式|功能|管理)?$"
                        + "|^(取消技能|exit|quit|/exit|/quit)$");
    }

    /**
     * Skill 选择策略: 高置信激活 > 模糊命中待确认 > 会话保持(TTL) > 无 Skill。
     *
     * <p>模糊命中时不直接激活技能模式，而是记录候选 Skill 返回 {@code CONFIRM}，
     * 由模型先询问用户；用户显式肯定（"是/对/好"）后才正式激活。
     * 即使同一 Skill 反复模糊命中也不会自动激活——"取消任务"这类泛化消息
     * 会与猎聘 description 产生 0.5x 相似度，自动激活会锁死工具集。</p>
     */
    private SkillSelectionResult pickSkill(String text, String userId) {
        // 优先 Embedding/关键词匹配
        SkillSelectionResult selection = skillSelector.select(text);
        if (selection.isActivate()) {
            // 高置信命中：清掉残留待确认，直接激活
            skillSessionManager.removePending(userId);
            log.debug("[LLM] Skill 命中: userId={}, skill={}", userId, selection.skill().name());
            return selection;
        }
        if (selection.isConfirm()) {
            // 已在活跃技能模式（TTL 内）：保持原技能，不降级。避免"确定投递"这类技能内指令
            // 被误判为模糊命中而降级为普通对话，导致工具解锁、模型编造执行结果。
            SkillSelectionResult active = keepAliveIfActive(userId);
            if (active.isActivate()) {
                skillSessionManager.removePending(userId);
                log.info("[LLM] 技能模式内模糊命中，保持原技能: userId={}, skill={}",
                        userId, active.skill().name());
                return active;
            }
            // 用户上轮已被询问技能意图，本轮给出明确肯定（"好""对""用"等）且消息再次
            // 落在模糊命中区间时，消费 pending 直接激活，避免无限"待确认"循环。
            SkillSession pending = skillSessionManager.getPending(userId, skillTtlMs);
            if (pending != null && pending.skillName().equals(selection.skill().name())
                    && isAffirmativeFollowUp(text)) {
                skillSessionManager.removePending(userId);
                SkillDefinition pendingSkill = skillRegistry.findEnabledByName(pending.skillName()).orElse(null);
                if (pendingSkill != null) {
                    log.info("[LLM] 用户确认Skill(模糊重命中): userId={}, skill={}",
                            userId, pendingSkill.name());
                    return SkillSelectionResult.activate(pendingSkill);
                }
            }
            // 模糊命中一律只询问，绝不因"重提同技能"自动激活——"取消任务"这类泛化消息会与
            // 猎聘 description 产生 0.5x 相似度，自动激活会锁死工具、导致提醒等无法处理。
            skillSessionManager.setPending(userId, selection.skill().name());
            return selection;
        }
        // 未命中：检查是否存在待确认 Skill（用户上轮被询问后的回复）
        SkillSession pending = skillSessionManager.getPending(userId, skillTtlMs);
        if (pending != null) {
            skillSessionManager.removePending(userId);
            SkillDefinition pendingSkill = skillRegistry.findEnabledByName(pending.skillName()).orElse(null);
            if (pendingSkill != null && isAffirmativeFollowUp(text)) {
                log.info("[LLM] 用户确认Skill: userId={}, skill={}", userId, pendingSkill.name());
                return SkillSelectionResult.activate(pendingSkill);
            }
            // 否定或换话题 → 放弃确认，回到普通对话
            log.info("[LLM] 放弃Skill确认: userId={}, skill={}", userId, pending.skillName());
        }
        // 未命中则检查会话保持（已激活 Skill 的 TTL 续期）
        return keepAliveIfActive(userId);
    }

    /**
     * 已激活 Skill 的会话保持（10 分钟 TTL）：多轮对话中不会因单条消息缺关键词而丢失上下文。
     */
    private SkillSelectionResult keepAliveIfActive(String userId) {
        SkillSession session = skillSessionManager.get(userId);
        if (session != null) {
            long elapsed = System.currentTimeMillis() - session.lastActiveAt();
            if (elapsed < skillTtlMs) {
                SkillDefinition skill = skillRegistry.findEnabledByName(session.skillName()).orElse(null);
                if (skill != null) {
                    return SkillSelectionResult.activate(skill);
                }
            }
            log.debug("[LLM] Skill 会话过期: userId={}, skill={}, elapsed={}ms",
                    userId, session.skillName(), elapsed);
            skillSessionManager.remove(userId);
        }
        return SkillSelectionResult.none();
    }

    private void compressIfNeeded(String userId, ChatResponse response) {
        // getMetadata() 在部分 Provider 可能返回 null，需判空避免 NPE
        ChatResponseMetadata metadata = response.getMetadata();
        if (metadata == null) return;
        Usage usage = metadata.getUsage();
        if (usage != null) {
            memoryManagerService.compressIfNeeded(userId, usage.getPromptTokens());
        }
    }

    /**
     * 截断过长文本用于日志输出。
     */
    private static String abbrev(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
