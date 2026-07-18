package ykd.ykd.llm.service.impl;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import lombok.extern.slf4j.Slf4j;
import ykd.ykd.llm.service.LlmService;
import ykd.ykd.llm.tools.ImageTools;
import ykd.ykd.llm.tools.WeatherTools;

import java.net.URI;

@Slf4j
@Service
public class LlmServiceImpl implements LlmService {

    private final WeatherTools weatherTools;
    private final ImageTools imageTools;

    public LlmServiceImpl(WeatherTools weatherTools, ImageTools imageTools) {
        this.weatherTools = weatherTools;
        this.imageTools = imageTools;
    }

    @Override
    public String chat(String text, String imageUrl, ChatClient client) {
        long start = System.currentTimeMillis();
        if ((text == null || text.isBlank()) && imageUrl != null) {
            text = "请描述这张图片";
        }
        String finalText = text;
        String textPreview = finalText != null ? (finalText.length() > 100 ? finalText.substring(0, 100) + "..." : finalText) : null;
        log.info("[LLM] 请求开始: text={}, hasImage={}", textPreview, imageUrl != null);
        try {
            String content = client.prompt()
                    .user(userSpec -> {
                        if (finalText != null && !finalText.isBlank()) {
                            userSpec.text(finalText);
                        }
                        if (imageUrl != null) {
                            userSpec.media(new Media(MimeTypeUtils.IMAGE_JPEG, URI.create(imageUrl)));
                        }
                    })
                    .tools(weatherTools, imageTools)
                    .call()
                    .content();
            long elapsed = System.currentTimeMillis() - start;
            String replyPreview = content != null ? (content.length() > 200 ? content.substring(0, 200) + "..." : content) : null;
            log.info("[LLM] 请求完成: elapsed={}ms, reply={}", elapsed, replyPreview);
            return content;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[LLM] 请求异常: elapsed={}ms, text={}, error={}", elapsed, textPreview, e.getMessage(), e);
            throw e;
        }
    }
}
