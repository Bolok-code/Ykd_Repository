package com.clitoolbox.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 从 application.yml / 环境变量绑定 DeepSeek 配置。
 */
@ConfigurationProperties(prefix = "app.deepseek")
public record DeepSeekProperties(
        String apiKey,
        URI baseUrl,
        String model,
        int maxTokens,
        int timeoutSeconds,
        boolean thinkingEnabled,
        int historyRounds) {

    public DeepSeekConfig toConfig() {
        return new DeepSeekConfig(
                apiKey,
                baseUrl,
                model,
                maxTokens,
                Duration.ofSeconds(timeoutSeconds),
                thinkingEnabled,
                historyRounds);
    }

    @Override
    public String toString() {
        return "DeepSeekProperties[apiKey=***, baseUrl=" + baseUrl
                + ", model=" + model
                + ", maxTokens=" + maxTokens
                + ", timeoutSeconds=" + timeoutSeconds
                + ", thinkingEnabled=" + thinkingEnabled
                + ", historyRounds=" + historyRounds + "]";
    }
}
