package com.clitoolbox.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.clitoolbox.exception.CliException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BailianTtsConfigTest {

    @Test
    void acceptsSupportedPcmSampleRate() {
        BailianTtsConfig config = new BailianTtsConfig(
                "qwen-audio-3.0-tts-flash",
                "longanlingxi",
                16_000,
                Duration.ofSeconds(180));

        assertEquals(16_000, config.sampleRate());
    }

    @Test
    void rejectsUnsupportedSampleRate() {
        assertThrows(
                CliException.class,
                () -> new BailianTtsConfig(
                        "qwen-audio-3.0-tts-flash",
                        "longanlingxi",
                        12_345,
                        Duration.ofSeconds(180)));
    }
}
