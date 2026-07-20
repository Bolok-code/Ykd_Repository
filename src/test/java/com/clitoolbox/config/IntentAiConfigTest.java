package com.clitoolbox.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.clitoolbox.exception.CliException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class IntentAiConfigTest {

    @Test
    void normalizesBaseUrlAndProtectsApiKeyInToString() {
        IntentAiConfig config = new IntentAiConfig(
                "secret-key",
                URI.create("https://ark.cn-beijing.volces.com/api/v3/"),
                "doubao-seed-2-0-mini-260428",
                350,
                Duration.ofSeconds(10),
                0.70);

        assertEquals(
                "https://ark.cn-beijing.volces.com/api/v3",
                config.baseUrl().toString());
        assertFalse(config.toString().contains("secret-key"));
    }

    @Test
    void rejectsMissingApiKey() {
        assertThrows(
                CliException.class,
                () -> new IntentAiConfig(
                        "",
                        URI.create(IntentAiConfig.DEFAULT_BASE_URL),
                        IntentAiConfig.DEFAULT_MODEL,
                        IntentAiConfig.DEFAULT_MAX_TOKENS,
                        Duration.ofSeconds(IntentAiConfig.DEFAULT_TIMEOUT_SECONDS),
                        IntentAiConfig.DEFAULT_CONFIDENCE_THRESHOLD));
    }

    @Test
    void rejectsUnsafeConfidenceThreshold() {
        assertThrows(
                CliException.class,
                () -> new IntentAiConfig(
                        "test-key",
                        URI.create(IntentAiConfig.DEFAULT_BASE_URL),
                        IntentAiConfig.DEFAULT_MODEL,
                        IntentAiConfig.DEFAULT_MAX_TOKENS,
                        Duration.ofSeconds(IntentAiConfig.DEFAULT_TIMEOUT_SECONDS),
                        0.20));
    }
}
