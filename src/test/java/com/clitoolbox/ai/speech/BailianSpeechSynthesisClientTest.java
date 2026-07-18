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
    void calculatesMpeg2Layer3Duration() {
        int frameCount = 10;
        int frameLength = 144;
        byte[] mp3 = new byte[frameCount * frameLength];
        int header = 0xFFE00000
                | (2 << 19)
                | (1 << 17)
                | (1 << 16)
                | (4 << 12)
                | (2 << 10);
        for (int frame = 0; frame < frameCount; frame++) {
            int offset = frame * frameLength;
            mp3[offset] = (byte) (header >>> 24);
            mp3[offset + 1] = (byte) (header >>> 16);
            mp3[offset + 2] = (byte) (header >>> 8);
            mp3[offset + 3] = (byte) header;
        }

        assertEquals(
                360,
                BailianSpeechSynthesisClient.calculateMp3PlayTimeMs(mp3));
    }
}
