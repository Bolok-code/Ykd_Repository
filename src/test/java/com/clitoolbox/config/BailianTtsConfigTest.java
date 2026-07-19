package com.clitoolbox.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.clitoolbox.exception.CliException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BailianTtsConfigTest {

    @Test
    void acceptsSupportedAudioFileConfiguration() {
        BailianTtsConfig config = new BailianTtsConfig(
                "qwen-audio-3.0-tts-flash",
                "longanlingxi",
                "MP3",
                16_000,
                Duration.ofSeconds(180));

        assertEquals("mp3", config.format());
        assertEquals(16_000, config.sampleRate());
    }

    @Test
    void rejectsUnsupportedSampleRate() {
        assertThrows(
                CliException.class,
                () -> new BailianTtsConfig(
                        "qwen-audio-3.0-tts-flash",
                        "longanlingxi",
                        "wav",
                        12_345,
                        Duration.ofSeconds(180)));
    }

    @Test
    void rejectsUnsupportedAudioFormat() {
        assertThrows(
                CliException.class,
                () -> new BailianTtsConfig(
                        "qwen-audio-3.0-tts-flash",
                        "longanlingxi",
                        "silk",
                        24_000,
                        Duration.ofSeconds(180)));
    }
}
