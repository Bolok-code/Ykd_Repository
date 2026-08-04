package ykd.ykd.llm.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import ykd.ykd.llm.tools.*;
import ykd.ykd.memory.MemoryManagerService;
import ykd.ykd.llm.service.LlmService;
import ykd.ykd.skill.model.SkillDefinition;
import ykd.ykd.skill.registry.SkillRegistry;
import ykd.ykd.skill.selector.SkillSelector;
import ykd.ykd.skill.session.SkillSessionManager;
import ykd.ykd.skill.session.SkillSessionManager.SkillSession;
import ykd.ykd.skill.tool.SkillToolResolver;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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

    /** Skill 会话保持 10 分钟，多轮对话中不会因单条消息无关键词而丢失 */
    private static final long SKILL_TTL_MS = 10 * 60 * 1000;

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
        return skillSessionManager.remove(userId) != null;
    }

    @Override
    public String chat(String text, List<String> imageUrls, ChatClient client, String userId, String systemContext) {
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

        String finalText = text;
        log.info("[LLM] 请求开始: userId={}, text={}, imageCount={}",
                userId, abbrev(finalText, 100), hasImages ? imageUrls.size() : 0);

        try {
            // 1. 加载当前用户的对话历史
            List<Message> history = memoryManagerService.getHistory(userId);
            // 2. Skill 匹配（图片消息不匹配 Skill，走视觉模型独立处理）
            //    优先级: 关键词命中 > 会话保持（10 分钟 TTL） > 无 Skill
            Optional<SkillDefinition> selectedSkill = hasImages
                    ? Optional.empty()
                    : pickSkill(finalText, userId);

            // 3. 构建请求消息列表（复制历史，避免 Skill 提示词被写入长期记忆）
            List<Message> requestMessages = new ArrayList<>(history);

            // 3a. 注入系统上下文（文档内容等），让 LLM 在回复时参考
            if (systemContext != null && !systemContext.isBlank()) {
                requestMessages.add(new SystemMessage(systemContext));
                log.debug("[LLM] 已注入系统上下文: length={}", systemContext.length());
            }
            // 激活或续期 Skill 会话
            selectedSkill.ifPresent(skill -> {
                skillSessionManager.activate(userId, skill.name());
                requestMessages.add(0, new SystemMessage(buildSkillPrompt(skill)));
                log.info("[LLM] 启用Skill: userId={}, skill={}, tools={}",
                        userId, skill.name(), skill.tools().size());
            });

            // 4. 组装请求
            ChatClient.ChatClientRequestSpec requestSpec = client.prompt()
                    .messages(requestMessages)
                    .user(userSpec -> buildUserMessage(userSpec, finalText, imageUrls));

            // 5. 注册工具 — Skill 模式 vs 普通模式
            requestSpec.tools(selectedSkill.isPresent()
                    ? resolveSkillTools(selectedSkill.get(), userId)
                    : defaultTools());

            // 6. 调用 LLM
            ChatResponse chatResponse = requestSpec.call().chatResponse();
            String content = chatResponse.getResult().getOutput().getText();
            // 7. 持久化对话记忆
            memoryManagerService.save(userId, finalText, content, hasImages ? "Agnes" : "DeepSeek");
            compressIfNeeded(userId, chatResponse);

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

                ===== Skill执行说明 =====

                %s

                ===== Skill执行说明结束 =====
                """.formatted(skill.name(), skill.description(), skill.instructions());
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
            if (removed != null) {
                log.info("[LLM] 手动退出Skill: userId={}, skill={}", userId, removed.skillName());
                return "已退出" + removed.skillName() + "技能模式，回到普通对话。";
            }
            return "当前没有活跃的技能模式。";
        }
        return null;
    }

    /**
     * 退出指令匹配（去空格转小写后）。
     * 容忍"推出"（"退出"常见拼音错别字）、"退出简历skill"这类中间词；
     * 全程锚定，避免"取消投递""退出投递计划"等计划管理指令被误拦截。
     */
    private static boolean isExitCommand(String normalized) {
        return normalized.matches(
                "^(退出|推出)(skill|技能|猎聘|投递|求职|搜索|简历|知识库)(skill|技能)?$"
                        + "|^(关闭|结束)(skill|技能|猎聘|投递|求职|搜索|知识库)$"
                        + "|^(取消技能|exit|quit|/exit|/quit)$");
    }

    /**
     * Skill 选择策略: 关键词命中 > 会话保持(TTL) > 无 Skill。
     * 多轮对话中不会因单条消息缺关键词而丢失 Skill 上下文。
     */
    private Optional<SkillDefinition> pickSkill(String text, String userId) {
        // 优先关键词匹配
        Optional<SkillDefinition> matched = skillSelector.select(text);
        if (matched.isPresent()) {
            log.debug("[LLM] Skill 关键词命中: userId={}, skill={}", userId, matched.get().name());
            return matched;
        }
        // 未命中则检查会话保持
        SkillSession session = skillSessionManager.get(userId);
        if (session != null) {
            long elapsed = System.currentTimeMillis() - session.lastActiveAt();
            if (elapsed < SKILL_TTL_MS) {
                return skillRegistry.findEnabledByName(session.skillName());
            }
            log.debug("[LLM] Skill 会话过期: userId={}, skill={}, elapsed={}ms",
                    userId, session.skillName(), elapsed);
            skillSessionManager.remove(userId);
        }
        return Optional.empty();
    }

    private void compressIfNeeded(String userId, ChatResponse response) {
        Usage usage = response.getMetadata().getUsage();
        if (usage != null) {
            memoryManagerService.compressIfNeeded(userId, (int) usage.getPromptTokens());
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
