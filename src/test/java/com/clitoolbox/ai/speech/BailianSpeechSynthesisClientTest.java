package com.clitoolbox.ai.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

class BailianSpeechSynthesisClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractsTrustedAudioUrlAndUpgradesItToHttps() throws Exception {
        var response = objectMapper.readTree("""
                {
                  "output": {
                    "audio": {
                      "url": "http://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/reply.pcm?Expires=1&Signature=a%2Bb%2Fc%3D&OSSAccessKeyId=test"
                    }
                  }
                }
                """);

        URI result = BailianSpeechSynthesisClient.extractAudioUri(response);

        assertEquals(
                "https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/reply.pcm?Expires=1&Signature=a%2Bb%2Fc%3D&OSSAccessKeyId=test",
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
        int oneSecondAt24Khz = 24_000 * 2;

        assertEquals(
                1_000,
                BailianSpeechSynthesisClient.calculatePcmPlayTimeMs(
                        oneSecondAt24Khz,
                        24_000));
    }

    @Test
    void parsesDashScopeErrorDetails() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-DashScope-Request-Id", "request-from-header");
        RestClientResponseException responseException = responseException(
                429,
                """
                {
                  "code": "Throttling.RateQuota",
                  "message": "Requests rate limit exceeded",
                  "request_id": "request-from-body"
                }
                """,
                headers);

        var error =
                BailianSpeechSynthesisClient.parseApiError(responseException);

        assertEquals("Throttling.RateQuota", error.code());
        assertEquals("Requests rate limit exceeded", error.message());
        assertEquals("request-from-body", error.requestId());
    }

    @Test
    void parsesOssXmlSignatureErrorAndMapsDownloadFailure() {
        RestClientResponseException responseException = responseException(
                403,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <Error>
                  <Code>SignatureDoesNotMatch</Code>
                  <Message>The request signature does not match.</Message>
                  <RequestId>oss-request-id</RequestId>
                </Error>
                """,
                HttpHeaders.EMPTY);

        var apiError =
                BailianSpeechSynthesisClient.parseApiError(responseException);
        CliException result = BailianSpeechSynthesisClient.mapResponseError(
                "下载合成结果",
                responseException,
                apiError);

        assertEquals("SignatureDoesNotMatch", apiError.code());
        assertEquals("oss-request-id", apiError.requestId());
        assertEquals(ErrorCode.NETWORK_ERROR, result.getErrorCode());
        assertTrue(result.getUserMessage().contains("OSS 签名不匹配"));
    }

    @Test
    void mapsAllocationQuotaToSpecificConfigurationError() {
        RestClientResponseException responseException = responseException(
                429,
                """
                {
                  "code": "Throttling.AllocationQuota",
                  "message": "Allocated quota exceeded",
                  "request_id": "quota-request"
                }
                """,
                HttpHeaders.EMPTY);

        CliException result = BailianSpeechSynthesisClient.mapResponseError(
                "提交合成任务",
                responseException,
                BailianSpeechSynthesisClient.parseApiError(responseException));

        assertEquals(ErrorCode.CONFIG_ERROR, result.getErrorCode());
        assertTrue(result.getUserMessage().contains("额度或分配配额不足"));
        assertTrue(result.getUserMessage().contains("quota-request"));
    }

    @Test
    void mapsInvalidApiKeyToSpecificError() {
        RestClientResponseException responseException = responseException(
                401,
                """
                {"code":"InvalidApiKey","message":"Invalid API-key provided."}
                """,
                HttpHeaders.EMPTY);

        CliException result = BailianSpeechSynthesisClient.mapResponseError(
                "提交合成任务",
                responseException,
                BailianSpeechSynthesisClient.parseApiError(responseException));

        assertEquals(ErrorCode.CONFIG_ERROR, result.getErrorCode());
        assertTrue(result.getUserMessage().contains("API Key 无效"));
        assertTrue(result.getUserMessage().contains("HTTP 401"));
    }

    private static RestClientResponseException responseException(
            int status,
            String body,
            HttpHeaders headers) {
        return new RestClientResponseException(
                "test",
                status,
                "test",
                headers,
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }
}
