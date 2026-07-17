package com.clitoolbox.ai.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.clitoolbox.exception.CliException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.junit.jupiter.api.Test;

class BailianImageGenerationClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractsTrustedImageUrlFromSynchronousResponse() throws Exception {
        var response = objectMapper.readTree("""
                {
                  "output": {
                    "choices": [{
                      "message": {
                        "content": [{
                          "image": "https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/a.png?Expires=1"
                        }]
                      }
                    }]
                  }
                }
                """);

        URI result = BailianImageGenerationClient.extractImageUri(response);

        assertEquals(
                "https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/a.png?Expires=1",
                result.toString());
    }

    @Test
    void rejectsUntrustedOrMissingResultUrl() throws Exception {
        var untrusted = objectMapper.readTree("""
                {
                  "output": {
                    "choices": [{
                      "message": {
                        "content": [{
                          "image": "https://example.com/a.png"
                        }]
                      }
                    }]
                  }
                }
                """);
        var missing = objectMapper.readTree("""
                {"output": {"choices": []}}
                """);

        assertThrows(
                CliException.class,
                () -> BailianImageGenerationClient.extractImageUri(untrusted));
        assertThrows(
                CliException.class,
                () -> BailianImageGenerationClient.extractImageUri(missing));
    }
}
