package ykd.ykd.llm.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import ykd.ykd.llm.tools.*;
import ykd.ykd.memory.MemoryManagerService;
import ykd.ykd.llm.service.LlmService;

import java.net.URI;
import java.util.List;
@Slf4j
@RequiredArgsConstructor
@Service
public class LlmServiceImpl implements LlmService {

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
            String content = client.prompt()
                    .messages(history)
                    .user(userSpec -> {
                        if (finalText != null && !finalText.isBlank()) {
                            userSpec.text(finalText);
                        }
                        if (hasImages) {
                            for (String imageUrl : imageUrls) {
                                userSpec.media(new Media(MimeTypeUtils.IMAGE_JPEG, URI.create(imageUrl)));
                            }
                        }
                    })
                    .tools(
                            linkTools,
                            weatherTools,
                            imageTools,
                            videoTools,
                            voiceTools,
                            reminderTools,
                            locationTools,
                            calculatorTools,
                            translateTools,
                            emailTools
                    )
                    .call()
                    .content();

            memoryManagerService.save(userId, finalText, content);

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
}
