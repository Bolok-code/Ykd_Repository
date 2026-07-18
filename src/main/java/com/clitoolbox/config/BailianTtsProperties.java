package com.clitoolbox.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 百炼非实时语音合成配置。
 */
@ConfigurationProperties(prefix = "app.bailian.tts")
public record BailianTtsProperties(
        String model,
        String voice,
        int sampleRate,
        int timeoutSeconds) {

    public BailianTtsConfig toConfig() {
        return new BailianTtsConfig(
                model,
                voice,
                sampleRate,
                Duration.ofSeconds(timeoutSeconds));
    }
}
