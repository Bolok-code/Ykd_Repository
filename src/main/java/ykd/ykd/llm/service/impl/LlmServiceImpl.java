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
import ykd.ykd.skill.selector.SkillSelector;
import ykd.ykd.skill.tool.SkillToolResolver;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
@Service
public class LlmServiceImpl implements LlmService {
    private final WebSearchTools webSearchTools;
    private final LinkTools linkTools;
    private final WeatherTools weatherTools;
    private final ImageTools imageTools;
    private final VideoTools videoTools;
    private final VoiceTools voiceTools;
    private final ReminderTools reminderTools;
    private final LocationTools locationTools;
    private final MemoryManagerService memoryManagerService;
    private final CalculatorTools calculatorTools;
    private final TranslateTools translateTools;
    private final EmailTools emailTools;
    private final DocumentTools documentTools;
    private final SkillSelector skillSelector;
    private final SkillToolResolver skillToolResolver;


    @Override
    public String chat(String text, List<String> imageUrls, ChatClient client, String userId) {
        long start = System.currentTimeMillis();

        boolean hasImages = imageUrls != null && !imageUrls.isEmpty();
        if ((text == null || text.isBlank()) && hasImages) {
            text = "请描述这些图片";
        }
        String finalText = text;
        String textPreview = finalText != null ? (finalText.length() > 100 ? finalText.substring(0, 100) + "..." : finalText) : null;
        log.info("[LLM] 请求开始: userId={}, text={}, imageCount={}", userId, textPreview, hasImages ? imageUrls.size() : 0);
        try {
            List<Message> history = memoryManagerService.getHistory(userId);
            /*
             * 图片走Agnes视觉模型，当前猎聘Skill只处理文字请求。
             */
            Optional<SkillDefinition> selectedSkill =
                    hasImages
                            ? Optional.empty()
                            : skillSelector.select(finalText);
            /*
             * 创建新集合，不能直接修改history，
             * 避免Skill提示词被写入长期对话记忆。
             */
            List<Message> requestMessages =
                    new ArrayList<>(history);

            selectedSkill.ifPresent(skill -> {
                requestMessages.add(
                        0,
                        new SystemMessage(
                                buildSkillPrompt(skill)
                        )
                );

                log.info(
                        "[LLM] 本次请求启用Skill: userId={}, skill={}, tools={}",
                        userId,
                        skill.name(),
                        skill.tools()
                );
            });

            ChatClient.ChatClientRequestSpec requestSpec = client.prompt()
                    .messages(requestMessages)
                    .user(userSpec -> {
                        if (finalText != null && !finalText.isBlank()) {
                            userSpec.text(finalText);
                        }
                        if (hasImages) {
                            for (String imageUrl : imageUrls) {
                                userSpec.media(new Media(MimeTypeUtils.IMAGE_JPEG, URI.create(imageUrl)));
                            }
                        }
                    });

            if (selectedSkill.isPresent()) {
                SkillDefinition skill = selectedSkill.get();
                ToolCallback[] skillTools =
                        skillToolResolver.resolve(skill);

                requestSpec.tools((Object[]) skillTools);

                log.info(
                        "[LLM] 已限制为Skill工具: userId={}, skill={}, toolCount={}",
                        userId,
                        skill.name(),
                        skillTools.length
                );
            } else {
                /*
                 * 普通请求继续使用原有工具，但不暴露猎聘工具。
                 * 猎聘工具只能在命中liepin-auto-apply Skill后使用。
                 */
                requestSpec.tools(
                        linkTools,
                        weatherTools,
                        imageTools,
                        videoTools,
                        voiceTools,
                        reminderTools,
                        locationTools,
                        calculatorTools,
                        translateTools,
                        emailTools,
                        documentTools,
                        webSearchTools
                );
            }

            ChatResponse chatResponse = requestSpec
                    .call()
                    .chatResponse();

            String content = chatResponse.getResult().getOutput().getText();
            String modelName = hasImages ? "Agnes" : "DeepSeek";
            memoryManagerService.save(userId, finalText, content, modelName);

            Usage usage = chatResponse.getMetadata().getUsage();
            if (usage != null) {
                int promptTokens = (int) usage.getPromptTokens();
                memoryManagerService.compressIfNeeded(userId, promptTokens);
            }

            long elapsed = System.currentTimeMillis() - start;
            String replyPreview = content != null ? (content.length() > 200 ? content.substring(0, 200) + "..." : content) : null;
            log.info("[LLM] 请求完成: elapsed={}ms, userId={}, reply={}", elapsed, userId, replyPreview);
            return content;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[LLM] 请求异常: elapsed={}ms, userId={}, text={}, error={}", elapsed, userId, textPreview, e.getMessage(), e);
            throw e;
        }
    }
    /**
     * 将Skill转换成本次请求使用的系统消息。
     */
    private String buildSkillPrompt(
            SkillDefinition skill) {

        return """
            当前请求已启用一个专业Skill。

            Skill名称：%s
            Skill描述：%s

            请严格遵守下面的Skill执行流程、安全规则和输出要求。
            不得虚构工具执行结果，不得跳过用户确认步骤。

            ===== Skill执行说明 =====

            %s

            ===== Skill执行说明结束 =====
            """.formatted(
                skill.name(),
                skill.description(),
                skill.instructions()
        );
    }
}
