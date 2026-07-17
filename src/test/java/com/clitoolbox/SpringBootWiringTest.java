package com.clitoolbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.clitoolbox.ai.AiChatClient;
import com.clitoolbox.ai.SpringAiDeepSeekClient;
import com.clitoolbox.config.DeepSeekConfig;
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
    }
}
