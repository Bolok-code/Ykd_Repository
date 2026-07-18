package com.clitoolbox.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.clitoolbox.exception.CliException;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SilkEncoderConfigTest {

    @Test
    void normalizesValidConfiguration() {
        SilkEncoderConfig config = new SilkEncoderConfig(
                " node ",
                Path.of("tools/silk-encoder/encode.mjs"),
                Path.of("work/silk"),
                Duration.ofSeconds(30));

        assertEquals("node", config.nodeCommand());
        assertEquals(
                Path.of("tools/silk-encoder/encode.mjs")
                        .toAbsolutePath()
                        .normalize(),
                config.scriptPath());
    }

    @Test
    void rejectsInvalidTimeout() {
        assertThrows(
                CliException.class,
                () -> new SilkEncoderConfig(
                        "node",
                        Path.of("encode.mjs"),
                        Path.of("work/silk"),
                        Duration.ZERO));
    }
}
