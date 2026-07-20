package com.clitoolbox.ai.intent;

import com.clitoolbox.config.IntentAiConfig;
import com.clitoolbox.config.IntentAiProperties;
import com.clitoolbox.intent.IntentClassifier;
import com.clitoolbox.weather.WeatherService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.client.RestClient;

/**
 * 豆包意图模型的 Spring Bean 配置。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IntentAiProperties.class)
public class IntentAiConfiguration {

    @Bean
    @Lazy
    IntentAiConfig intentAiConfig(IntentAiProperties properties) {
        return properties.toConfig();
    }

    @Bean("doubaoIntentChatModel")
    @Lazy
    ChatModel doubaoIntentChatModel(
            IntentAiConfig config,
            RestClient.Builder restClientBuilder) {
        return SpringAiDoubaoIntentClassifier.createChatModel(
                config,
                restClientBuilder);
    }

    @Bean
    @Lazy
    IntentClassifier intentClassifier(
            IntentAiConfig config,
            @Qualifier("doubaoIntentChatModel") ChatModel chatModel,
            ObjectMapper objectMapper) {
        return new SpringAiDoubaoIntentClassifier(
                config,
                chatModel,
                objectMapper,
                Clock.system(WeatherService.WEATHER_ZONE));
    }
}
