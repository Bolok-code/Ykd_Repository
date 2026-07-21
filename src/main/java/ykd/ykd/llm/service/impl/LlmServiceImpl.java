package ykd.ykd.llm.service.impl;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import lombok.extern.slf4j.Slf4j;
import ykd.ykd.memory.MemoryManagerService;
import ykd.ykd.llm.service.LlmService;
import ykd.ykd.llm.tools.ImageTools;
import ykd.ykd.llm.tools.VideoTools;
import ykd.ykd.llm.tools.VoiceTools;
import ykd.ykd.llm.tools.WeatherTools;

import java.net.URI;
import java.util.List;

@Slf4j
@Service
public class LlmServiceImpl implements LlmService {

    private final WeatherTools weatherTools;
    private final ImageTools imageTools;
    private final VideoTools videoTools;
    private final VoiceTools voiceTools;
    private final MemoryManagerService memoryManagerService;

    public LlmServiceImpl(WeatherTools weatherTools, ImageTools imageTools,
                          VideoTools videoTools, VoiceTools voiceTools,
                          MemoryManagerService memoryManagerService) {
        this.weatherTools = weatherTools;
        this.imageTools = imageTools;
        this.videoTools = videoTools;
        this.voiceTools = voiceTools;
        this.memoryManagerService = memoryManagerService;
    }

    @Override
    public String chat(String text, String imageUrl, ChatClient client, String userId) {
        long start = System.currentTimeMillis();

        if ((text == null || text.isBlank()) && imageUrl != null) {
            text = "请描述这张图片";
        }
        String finalText = text;
        String textPreview = finalText != null ? (finalText.length() > 100 ? finalText.substring(0, 100) + "..." : finalText) : null;
        log.info("[LLM] 请求开始: userId={}, text={}, hasImage={}", userId, textPreview, imageUrl != null);
        try {
            List<Message> history = memoryManagerService.getHistory(userId);
            String content = client.prompt()
                    .messages(history)
                    .user(userSpec -> {
                        if (finalText != null && !finalText.isBlank()) {
                            userSpec.text(finalText);
                        }
                        if (imageUrl != null) {
                            userSpec.media(new Media(MimeTypeUtils.IMAGE_JPEG, URI.create(imageUrl)));
                        }
                    })
                    .tools(weatherTools, imageTools, videoTools, voiceTools)
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
