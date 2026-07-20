package com.clitoolbox.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 从 application.yml / 环境变量绑定豆包意图识别配置。
 */
@ConfigurationProperties(prefix = "app.intent-ai")
public record IntentAiProperties(
        String apiKey,
        URI baseUrl,
        String model,
        int maxTokens,
        int timeoutSeconds,
        double confidenceThreshold) {

    public IntentAiConfig toConfig() {
        return new IntentAiConfig(
                apiKey,
                baseUrl,
                model,
                maxTokens,
                Duration.ofSeconds(timeoutSeconds),
                confidenceThreshold);
    }

    @Override
    public String toString() {
        return "IntentAiProperties[apiKey=***, baseUrl=" + baseUrl
                + ", model=" + model
                + ", maxTokens=" + maxTokens
                + ", timeoutSeconds=" + timeoutSeconds
                + ", confidenceThreshold=" + confidenceThreshold + "]";
    }
}
