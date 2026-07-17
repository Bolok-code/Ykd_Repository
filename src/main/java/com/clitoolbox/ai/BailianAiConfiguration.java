package com.clitoolbox.ai;

import com.clitoolbox.ai.image.BailianImageGenerationClient;
import com.clitoolbox.ai.image.ImageGenerationClient;
import com.clitoolbox.ai.vision.ImageUnderstandingClient;
import com.clitoolbox.ai.vision.SpringAiBailianVisionClient;
import com.clitoolbox.config.BailianConfig;
import com.clitoolbox.config.BailianProperties;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.client.RestClient;

/**
 * 百炼视觉理解和 Qwen-Image 文生图的 Spring Bean 配置。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BailianProperties.class)
public class BailianAiConfiguration {

    @Bean
    @Lazy
    BailianConfig bailianConfig(BailianProperties properties) {
        return properties.toConfig();
    }

    @Bean
    @Lazy
    ImageUnderstandingClient imageUnderstandingClient(
            BailianConfig config,
            RestClient.Builder restClientBuilder) {
        ChatModel chatModel =
                SpringAiBailianVisionClient.createChatModel(config, restClientBuilder);
        return new SpringAiBailianVisionClient(config, chatModel);
    }

    @Bean
    @Lazy
    ImageGenerationClient imageGenerationClient(
            BailianConfig config,
            RestClient.Builder restClientBuilder) {
        return BailianImageGenerationClient.create(config, restClientBuilder);
    }
}
