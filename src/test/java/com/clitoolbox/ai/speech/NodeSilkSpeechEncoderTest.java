package com.clitoolbox.ai.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.clitoolbox.config.SilkEncoderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeSilkSpeechEncoderTest {

    @TempDir
    Path tempDirectory;

    @Test
    void encodesPcmThroughProjectSilkWasmTool() throws Exception {
        Path script = Path.of("tools/silk-encoder/encode.mjs")
                .toAbsolutePath()
                .normalize();
        Assumptions.assumeTrue(Files.isRegularFile(script));
        Assumptions.assumeTrue(Files.isDirectory(
                script.getParent().resolve("node_modules").resolve("silk-wasm")));
        Assumptions.assumeTrue(nodeIsAvailable());

        int sampleRate = 24_000;
        GeneratedSpeech pcm = new GeneratedSpeech(
                new byte[sampleRate * 2],
                "test.pcm",
                1_000,
                sampleRate,
                1,
                16);
        NodeSilkSpeechEncoder encoder = new NodeSilkSpeechEncoder(
                new SilkEncoderConfig(
                        "node",
                        script,
                        tempDirectory,
                        Duration.ofSeconds(10)),
                new ObjectMapper());

        GeneratedSpeech silk = encoder.encode(pcm);

        NodeSilkSpeechEncoder.validateSilk(silk.data());
        assertEquals(6, silk.encodeType());
        assertEquals(24_000, silk.sampleRate());
        assertTrue(silk.playTimeMs() >= 980 && silk.playTimeMs() <= 1_020);
    }

    private static boolean nodeIsAvailable() {
        try {
            Process process = new ProcessBuilder("node", "--version")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(5, TimeUnit.SECONDS)
                    && process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}
