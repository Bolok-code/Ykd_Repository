package com.clitoolbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.clitoolbox.ai.AiChatClient;
import com.clitoolbox.ai.SpringAiDeepSeekClient;
import com.clitoolbox.ai.image.ImageGenerationClient;
import com.clitoolbox.ai.speech.SpeechSynthesisClient;
import com.clitoolbox.ai.speech.SpeechEncoder;
import com.clitoolbox.ai.vision.ImageUnderstandingClient;
import com.clitoolbox.config.BailianConfig;
import com.clitoolbox.config.DeepSeekConfig;
import com.clitoolbox.config.VoiceReplyProperties;
import com.clitoolbox.ilink.service.ILinkService;
import com.clitoolbox.ilink.service.impl.ILinkServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(
        args = "help",
        properties = {
            "app.deepseek.api-key=test-api-key",
            "app.bailian.api-key=test-bailian-key",
            "app.bailian.workspace-id=test-workspace",
            "app.weather.api-key=test-weather-key",
            "logging.file.name="
        })
class SpringBootWiringTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void wiresSpringBootAndLazyDeepSeekBeans() {
        AiChatClient aiChatClient = applicationContext.getBean(AiChatClient.class);
        DeepSeekConfig config = applicationContext.getBean(DeepSeekConfig.class);

        assertTrue(aiChatClient instanceof SpringAiDeepSeekClient);
        assertEquals("deepseek-v4-flash", config.model());
        assertFalse(config.toString().contains("test-api-key"));
        ILinkService iLinkService = applicationContext.getBean(ILinkService.class);
        assertNotNull(iLinkService);
        assertInstanceOf(ILinkServiceImpl.class, iLinkService);
        BailianConfig bailianConfig = applicationContext.getBean(BailianConfig.class);
        assertEquals("qwen3.7-plus", bailianConfig.visionModel());
        assertEquals("qwen-image-2.0", bailianConfig.imageModel());
        assertEquals("1024*1024", bailianConfig.imageSize());
        assertFalse(bailianConfig.toString().contains("test-bailian-key"));
        assertNotNull(applicationContext.getBean(ImageUnderstandingClient.class));
        assertNotNull(applicationContext.getBean(ImageGenerationClient.class));
        assertNotNull(applicationContext.getBean(SpeechSynthesisClient.class));
        assertNotNull(applicationContext.getBean(SpeechEncoder.class));
        assertTrue(applicationContext
                .getBean(VoiceReplyProperties.class)
                .sendFileCopyAfterNative());
    }
}
