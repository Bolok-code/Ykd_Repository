package com.clitoolbox.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.clitoolbox.exception.CliException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BailianConfigTest {

    @Test
    void buildsWorkspaceSpecificBeijingEndpointsAndHidesApiKey() {
        BailianConfig config = new BailianConfig(
                "test-bailian-key",
                "ws-123",
                "cn-beijing",
                "qwen3.7-plus",
                "qwen-image-2.0",
                "1024x1024",
                Duration.ofSeconds(180));

        assertEquals(
                "https://ws-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
                config.compatibleBaseUrl().toString());
        assertEquals(
                "https://ws-123.cn-beijing.maas.aliyuncs.com/api/v1",
                config.dashScopeBaseUrl().toString());
        assertEquals("1024*1024", config.imageSize());
        assertFalse(config.toString().contains("test-bailian-key"));
    }

    @Test
    void rejectsMissingWorkspaceAndOutOfRangeQwenImageSize() {
        assertThrows(CliException.class, () -> new BailianConfig(
                "key",
                "",
                "cn-beijing",
                "qwen3.7-plus",
                "qwen-image-2.0",
                "1024*1024",
                Duration.ofSeconds(180)));
        assertThrows(CliException.class, () -> new BailianConfig(
                "key",
                "ws-123",
                "cn-beijing",
                "qwen3.7-plus",
                "qwen-image-2.0-pro",
                "4096*4096",
                Duration.ofSeconds(180)));
    }

    @Test
    void validatesQwenImagePlusFixedSizes() {
        assertThrows(CliException.class, () -> new BailianConfig(
                "key",
                "ws-123",
                "cn-beijing",
                "qwen3.7-plus",
                "qwen-image-plus",
                "1024*1024",
                Duration.ofSeconds(180)));

        BailianConfig config = new BailianConfig(
                "key",
                "ws-123",
                "cn-beijing",
                "qwen3.7-plus",
                "qwen-image-plus-2026-01-09",
                "1328*1328",
                Duration.ofSeconds(180));
        assertEquals("1328*1328", config.imageSize());
    }
}
