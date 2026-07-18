package com.clitoolbox.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 项目内 silk-wasm 编码工具配置。
 */
@ConfigurationProperties(prefix = "app.bailian.tts.silk")
public record SilkEncoderProperties(
        boolean enabled,
        String nodeCommand,
        String scriptPath,
        String workDirectory,
        int timeoutSeconds) {

    public SilkEncoderConfig toConfig() {
        return new SilkEncoderConfig(
                nodeCommand,
                Path.of(scriptPath),
                Path.of(workDirectory),
                Duration.ofSeconds(timeoutSeconds));
    }
}
