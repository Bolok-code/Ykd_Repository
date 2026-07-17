package com.clitoolbox.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 阿里云百炼视觉理解和图片生成配置。
 */
@ConfigurationProperties(prefix = "app.bailian")
public record BailianProperties(
        String apiKey,
        String workspaceId,
        String region,
        String visionModel,
        String imageModel,
        String imageSize,
        int timeoutSeconds) {

    public BailianConfig toConfig() {
        return new BailianConfig(
                apiKey,
                workspaceId,
                region,
                visionModel,
                imageModel,
                imageSize,
                Duration.ofSeconds(timeoutSeconds));
    }

    @Override
    public String toString() {
        return "BailianProperties[apiKey=***, workspaceId=" + workspaceId
                + ", region=" + region
                + ", visionModel=" + visionModel
                + ", imageModel=" + imageModel
                + ", imageSize=" + imageSize
                + ", timeoutSeconds=" + timeoutSeconds + "]";
    }
}
