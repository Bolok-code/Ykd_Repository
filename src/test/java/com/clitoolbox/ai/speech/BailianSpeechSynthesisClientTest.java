package com.clitoolbox.ai.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.clitoolbox.exception.CliException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.junit.jupiter.api.Test;

class BailianSpeechSynthesisClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractsTrustedAudioUrlAndUpgradesItToHttps() throws Exception {
        var response = objectMapper.readTree("""
                {
                  "output": {
                    "audio": {
                      "url": "http://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/reply.pcm?Expires=1"
                    }
                  }
                }
                """);

        URI result = BailianSpeechSynthesisClient.extractAudioUri(response);

        assertEquals(
                "https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/reply.pcm?Expires=1",
                result.toString());
    }

    @Test
    void rejectsUntrustedOrMissingAudioUrl() throws Exception {
        var untrusted = objectMapper.readTree("""
                {"output":{"audio":{"url":"https://example.com/reply.pcm"}}}
                """);
        var missing = objectMapper.readTree("""
                {"output":{"audio":{}}}
                """);

        assertThrows(
                CliException.class,
                () -> BailianSpeechSynthesisClient.extractAudioUri(untrusted));
        assertThrows(
                CliException.class,
                () -> BailianSpeechSynthesisClient.extractAudioUri(missing));
    }

    @Test
    void calculatesMonoSixteenBitPcmDuration() {
        int oneSecondAt16Khz = 16_000 * 2;

        assertEquals(
                1_000,
                BailianSpeechSynthesisClient.calculatePcmPlayTimeMs(
                        oneSecondAt16Khz,
                        16_000));
    }
}
