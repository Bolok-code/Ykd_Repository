package com.clitoolbox.ai;

import com.clitoolbox.ai.image.BailianImageGenerationClient;
import com.clitoolbox.ai.image.ImageGenerationClient;
import com.clitoolbox.ai.speech.BailianSpeechSynthesisClient;
import com.clitoolbox.ai.speech.NodeSilkSpeechEncoder;
import com.clitoolbox.ai.speech.SpeechEncoder;
import com.clitoolbox.ai.speech.SpeechSynthesisClient;
import com.clitoolbox.ai.vision.ImageUnderstandingClient;
import com.clitoolbox.ai.vision.SpringAiBailianVisionClient;
import com.clitoolbox.config.BailianConfig;
import com.clitoolbox.config.BailianProperties;
import com.clitoolbox.config.BailianTtsConfig;
import com.clitoolbox.config.BailianTtsProperties;
import com.clitoolbox.config.SilkEncoderConfig;
import com.clitoolbox.config.SilkEncoderProperties;
import com.clitoolbox.config.VoiceReplyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.client.RestClient;

/**
 * 百炼视觉理解、Qwen-Image 文生图和语音合成的 Spring Bean 配置。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    BailianProperties.class,
    BailianTtsProperties.class,
    SilkEncoderProperties.class,
    VoiceReplyProperties.class
})
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

    @Bean
    @Lazy
    BailianTtsConfig bailianTtsConfig(BailianTtsProperties properties) {
        return properties.toConfig();
    }

    @Bean
    @Lazy
    SpeechSynthesisClient speechSynthesisClient(
            BailianConfig bailianConfig,
            BailianTtsConfig ttsConfig,
            RestClient.Builder restClientBuilder) {
        return BailianSpeechSynthesisClient.create(
                bailianConfig,
                ttsConfig,
                restClientBuilder);
    }

    @Bean
    @Lazy
    @ConditionalOnProperty(
            prefix = "app.bailian.tts.silk",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    SilkEncoderConfig silkEncoderConfig(SilkEncoderProperties properties) {
        return properties.toConfig();
    }

    @Bean
    @Lazy
    @ConditionalOnProperty(
            prefix = "app.bailian.tts.silk",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    SpeechEncoder speechEncoder(
            SilkEncoderConfig config,
            ObjectMapper objectMapper) {
        return new NodeSilkSpeechEncoder(config, objectMapper);
    }
}
