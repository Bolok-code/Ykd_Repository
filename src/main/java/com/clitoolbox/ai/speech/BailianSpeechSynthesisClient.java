package com.clitoolbox.ai.speech;

import com.clitoolbox.config.BailianConfig;
import com.clitoolbox.config.BailianTtsConfig;
import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 调用百炼 Qwen-Audio-TTS 非实时接口，将文字合成为单声道 16 位 PCM。
 */
public final class BailianSpeechSynthesisClient implements SpeechSynthesisClient {
    private static final Logger LOG =
            LoggerFactory.getLogger(BailianSpeechSynthesisClient.class);
    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();
    static final int PCM_ENCODE_TYPE = 1;
    static final int AUDIO_BITS_PER_SAMPLE = 16;
    private static final int PCM_CHANNELS = 1;
    private static final int MAX_TEXT_CHARS = 5_000;
    private static final int MAX_HTTP_ATTEMPTS = 3;
    private static final long RETRY_BASE_DELAY_MS = 800L;
    private static final long MAX_RETRY_DELAY_MS = 5_000L;

    private final BailianTtsConfig config;
    private final RestClient synthesisClient;
    private final RestClient downloadClient;
    private final URI synthesisUri;

    public BailianSpeechSynthesisClient(
            BailianTtsConfig config,
            RestClient synthesisClient,
            RestClient downloadClient,
            URI synthesisUri) {
        this.config = config;
        this.synthesisClient = synthesisClient;
        this.downloadClient = downloadClient;
        this.synthesisUri = synthesisUri;
    }

    public static BailianSpeechSynthesisClient create(
            BailianConfig bailianConfig,
            BailianTtsConfig ttsConfig,
            RestClient.Builder restClientBuilder) {
        if (!"cn-beijing".equals(bailianConfig.region())) {
            throw new CliException(
                    ErrorCode.CONFIG_ERROR,
                    "Qwen-Audio-TTS 非实时语音合成目前需要使用 cn-beijing 地域。");
        }

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(ttsConfig.timeout())
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(ttsConfig.timeout());

        RestClient synthesisClient = restClientBuilder.clone()
                .requestFactory(requestFactory)
                .defaultHeader(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + bailianConfig.apiKey())
                .defaultHeader(
                        HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_JSON_VALUE)
                .build();
        RestClient downloadClient = restClientBuilder.clone()
                .requestFactory(requestFactory)
                .build();
        URI synthesisUri = URI.create(
                bailianConfig.dashScopeBaseUrl()
                        + "/services/audio/tts/SpeechSynthesizer");
        return new BailianSpeechSynthesisClient(
                ttsConfig,
                synthesisClient,
                downloadClient,
                synthesisUri);
    }

    @Override
    public GeneratedSpeech synthesize(String text) {
        String normalizedText = validateText(text);
        Map<String, Object> body = Map.of(
                "model", config.model(),
                "input", Map.of(
                        "text", normalizedText,
                        "voice", config.voice(),
                        "format", "pcm",
                        "sample_rate", config.sampleRate()));

        try {
            JsonNode response = executeWithRetry(
                    "提交合成任务",
                    () -> synthesisClient.post()
                            .uri(synthesisUri)
                            .body(body)
                            .retrieve()
                            .body(JsonNode.class));
            URI audioUri = extractAudioUri(response);
            return downloadGeneratedSpeech(audioUri);
        } catch (CliException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new CliException(
                    ErrorCode.NETWORK_ERROR,
                    "百炼语音合成请求超时或网络不可用，请稍后重试。",
                    e);
        } catch (RuntimeException e) {
            throw new CliException(
                    ErrorCode.NETWORK_ERROR,
                    "无法调用百炼语音合成服务，请检查网络和百炼配置。",
                    e);
        }
    }

    @Override
    public String modelName() {
        return config.model();
    }

    static URI extractAudioUri(JsonNode response) {
        if (response == null) {
            throw new CliException(ErrorCode.UNKNOWN, "百炼语音合成返回了空结果。");
        }
        String url = response.path("output")
                .path("audio")
                .path("url")
                .asText();
        if (!url.isBlank()) {
            return normalizeResultUri(URI.create(url));
        }
        String message = response.path("message").asText();
        throw new CliException(
                ErrorCode.UNKNOWN,
                message.isBlank()
                        ? "百炼语音合成没有返回音频。"
                        : "百炼语音合成失败：" + message);
    }

    static int calculatePcmPlayTimeMs(int byteLength, int sampleRate) {
        if (byteLength <= 0
                || byteLength % (AUDIO_BITS_PER_SAMPLE / 8) != 0
                || sampleRate <= 0) {
            throw new CliException(ErrorCode.UNKNOWN, "百炼返回的 PCM 音频信息不完整。");
        }
        long bytesPerSecond =
                (long) sampleRate * PCM_CHANNELS * AUDIO_BITS_PER_SAMPLE / 8;
        long playTimeMs = Math.max(1L, byteLength * 1_000L / bytesPerSecond);
        return Math.toIntExact(playTimeMs);
    }

    private GeneratedSpeech downloadGeneratedSpeech(URI audioUri) {
        ResponseEntity<byte[]> entity = executeWithRetry(
                "下载合成结果",
                () -> downloadClient.get()
                        .uri(audioUri)
                        .retrieve()
                        .toEntity(byte[].class));
        byte[] bytes = entity.getBody();
        if (bytes == null || bytes.length == 0) {
            throw new CliException(ErrorCode.UNKNOWN, "百炼生成的语音下载失败。");
        }
        int playTimeMs = calculatePcmPlayTimeMs(bytes.length, config.sampleRate());
        return new GeneratedSpeech(
                bytes,
                "bailian-reply.pcm",
                playTimeMs,
                config.sampleRate(),
                PCM_ENCODE_TYPE,
                AUDIO_BITS_PER_SAMPLE);
    }

    private <T> T executeWithRetry(String operation, Supplier<T> request) {
        for (int attempt = 1; attempt <= MAX_HTTP_ATTEMPTS; attempt++) {
            try {
                return request.get();
            } catch (RestClientResponseException e) {
                BailianApiError apiError = parseApiError(e);
                if (attempt < MAX_HTTP_ATTEMPTS && isRetryable(e, apiError)) {
                    long delayMs = retryDelayMs(e, attempt);
                    LOG.warn(
                            "百炼语音合成{}暂时失败，将重试 - attempt={}/{}, delayMs={}, "
                                    + "httpStatus={}, code={}, requestId={}, message={}",
                            operation,
                            attempt,
                            MAX_HTTP_ATTEMPTS,
                            delayMs,
                            e.getStatusCode().value(),
                            display(apiError.code()),
                            display(apiError.requestId()),
                            display(apiError.message()));
                    sleepBeforeRetry(delayMs);
                    continue;
                }
                throw mapResponseError(operation, e, apiError);
            } catch (ResourceAccessException e) {
                if (attempt < MAX_HTTP_ATTEMPTS) {
                    long delayMs = exponentialDelayMs(attempt);
                    LOG.warn(
                            "百炼语音合成{}发生网络异常，将重试 - attempt={}/{}, delayMs={}, message={}",
                            operation,
                            attempt,
                            MAX_HTTP_ATTEMPTS,
                            delayMs,
                            display(e.getMessage()));
                    sleepBeforeRetry(delayMs);
                    continue;
                }
                throw new CliException(
                        ErrorCode.NETWORK_ERROR,
                        "百炼语音合成" + operation + "超时或网络不可用，自动重试后仍然失败。",
                        e);
            }
        }
        throw new CliException(ErrorCode.UNKNOWN, "百炼语音合成重试状态异常。");
    }

    static BailianApiError parseApiError(RestClientResponseException error) {
        String code = "";
        String message = "";
        String requestId = "";
        String responseBody = error.getResponseBodyAsString(StandardCharsets.UTF_8);
        if (responseBody != null && !responseBody.isBlank()) {
            try {
                JsonNode root = ERROR_MAPPER.readTree(responseBody);
                code = firstText(root, "code", "Code");
                message = firstText(root, "message", "Message");
                requestId = firstText(
                        root,
                        "request_id",
                        "requestId",
                        "RequestId",
                        "request-id");
            } catch (Exception ignored) {
                code = xmlTagValue(responseBody, "Code");
                message = xmlTagValue(responseBody, "Message");
                requestId = xmlTagValue(responseBody, "RequestId");
                if (code.isBlank() && message.isBlank() && requestId.isBlank()) {
                    message = responseBody;
                }
            }
        }
        if (requestId.isBlank() && error.getResponseHeaders() != null) {
            requestId = firstHeader(
                    error.getResponseHeaders(),
                    "X-DashScope-Request-Id",
                    "X-Request-Id",
                    "Request-Id");
        }
        return new BailianApiError(
                normalizeDiagnostic(code),
                normalizeDiagnostic(message),
                normalizeDiagnostic(requestId));
    }

    static CliException mapResponseError(
            String operation,
            RestClientResponseException error,
            BailianApiError apiError) {
        int status = error.getStatusCode().value();
        String code = apiError.code().toLowerCase(Locale.ROOT);
        String diagnostic = diagnosticSuffix(status, apiError);
        String serviceMessage = apiError.message().isBlank()
                ? ""
                : " 百炼返回：" + apiError.message();

        LOG.warn(
                "百炼语音合成{}失败 - httpStatus={}, code={}, requestId={}, message={}",
                operation,
                status,
                display(apiError.code()),
                display(apiError.requestId()),
                display(apiError.message()));

        if (status == 403 && operation.contains("下载")) {
            String reason = code.contains("signaturedoesnotmatch")
                    ? "百炼语音下载地址的 OSS 签名不匹配。"
                    : "百炼语音下载地址无效、已过期或无权访问。";
            return new CliException(
                    ErrorCode.NETWORK_ERROR,
                    reason + serviceMessage + diagnostic,
                    error);
        }

        if (status == 429
                && (code.contains("allocationquota")
                || code.contains("insufficient_quota"))) {
            return new CliException(
                    ErrorCode.CONFIG_ERROR,
                    "百炼语音合成额度或分配配额不足，请检查套餐、余额和配额。"
                            + serviceMessage
                            + diagnostic,
                    error);
        }

        return switch (status) {
            case 400 -> new CliException(
                    ErrorCode.INVALID_INPUT,
                    "百炼无法合成当前文本，请检查文本长度、字符和语音参数。"
                            + serviceMessage
                            + diagnostic,
                    error);
            case 401 -> new CliException(
                    ErrorCode.CONFIG_ERROR,
                    "百炼 API Key 无效或已经失效。" + serviceMessage + diagnostic,
                    error);
            case 402 -> new CliException(
                    ErrorCode.CONFIG_ERROR,
                    "百炼账户余额或可用额度不足。" + serviceMessage + diagnostic,
                    error);
            case 403 -> new CliException(
                    ErrorCode.CONFIG_ERROR,
                    "百炼没有当前模型、音色或业务空间的访问权限。"
                            + serviceMessage
                            + diagnostic,
                    error);
            case 404 -> new CliException(
                    ErrorCode.CONFIG_ERROR,
                    "百炼语音模型或接口地址不存在，请检查地域和模型名称。"
                            + serviceMessage
                            + diagnostic,
                    error);
            case 408 -> new CliException(
                    ErrorCode.NETWORK_ERROR,
                    "百炼语音合成请求超时。" + serviceMessage + diagnostic,
                    error);
            case 429 -> new CliException(
                    ErrorCode.NETWORK_ERROR,
                    "百炼语音合成请求受到限流，自动重试后仍然失败，请稍后再试。"
                            + serviceMessage
                            + diagnostic,
                    error);
            default -> new CliException(
                    status >= 500 ? ErrorCode.NETWORK_ERROR : ErrorCode.UNKNOWN,
                    (status >= 500
                            ? "百炼语音合成服务暂时异常，自动重试后仍然失败。"
                            : "百炼语音合成请求失败。")
                            + serviceMessage
                            + diagnostic,
                    error);
        };
    }

    private static boolean isRetryable(
            RestClientResponseException error,
            BailianApiError apiError) {
        int status = error.getStatusCode().value();
        if (status >= 500) {
            return true;
        }
        if (status != 429) {
            return false;
        }
        String code = apiError.code().toLowerCase(Locale.ROOT);
        return !code.contains("allocationquota")
                && !code.contains("insufficient_quota");
    }

    private static long retryDelayMs(
            RestClientResponseException error,
            int attempt) {
        if (error.getResponseHeaders() != null) {
            String retryAfter =
                    error.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            if (retryAfter != null) {
                try {
                    long seconds = Long.parseLong(retryAfter.trim());
                    return Math.max(
                            RETRY_BASE_DELAY_MS,
                            Math.min(MAX_RETRY_DELAY_MS, seconds * 1_000L));
                } catch (NumberFormatException ignored) {
                    // HTTP 日期形式交由指数退避处理，避免引入系统时钟误差。
                }
            }
        }
        return exponentialDelayMs(attempt);
    }

    private static long exponentialDelayMs(int attempt) {
        return Math.min(
                MAX_RETRY_DELAY_MS,
                RETRY_BASE_DELAY_MS * (1L << Math.max(0, attempt - 1)));
    }

    private static void sleepBeforeRetry(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException(
                    ErrorCode.NETWORK_ERROR,
                    "百炼语音合成重试被中断。",
                    e);
        }
    }

    private static String firstText(JsonNode root, String... fieldNames) {
        if (root == null) {
            return "";
        }
        for (String fieldName : fieldNames) {
            String value = root.path(fieldName).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String firstHeader(HttpHeaders headers, String... names) {
        for (String name : names) {
            String value = headers.getFirst(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String xmlTagValue(String xml, String tagName) {
        if (xml == null || xml.isBlank()) {
            return "";
        }
        String openingTag = "<" + tagName + ">";
        String closingTag = "</" + tagName + ">";
        int valueStart = xml.indexOf(openingTag);
        if (valueStart < 0) {
            return "";
        }
        valueStart += openingTag.length();
        int valueEnd = xml.indexOf(closingTag, valueStart);
        if (valueEnd < 0) {
            return "";
        }
        return decodeXmlEntities(xml.substring(valueStart, valueEnd).trim());
    }

    private static String decodeXmlEntities(String value) {
        return value
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    private static String diagnosticSuffix(
            int status,
            BailianApiError apiError) {
        StringBuilder diagnostic = new StringBuilder("（HTTP ")
                .append(status);
        if (!apiError.code().isBlank()) {
            diagnostic.append("，错误码 ").append(apiError.code());
        }
        if (!apiError.requestId().isBlank()) {
            diagnostic.append("，RequestId ").append(apiError.requestId());
        }
        return diagnostic.append("）").toString();
    }

    private static String normalizeDiagnostic(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 300
                ? normalized
                : normalized.substring(0, 300) + "...";
    }

    private static String display(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String validateText(String text) {
        if (text == null || text.isBlank()) {
            throw new CliException(ErrorCode.INVALID_INPUT, "语音合成文本不能为空。");
        }
        String normalized = text.trim();
        if (normalized.length() > MAX_TEXT_CHARS) {
            throw new CliException(
                    ErrorCode.INVALID_INPUT,
                    "语音合成文本不能超过 5000 个字符。");
        }
        return normalized;
    }

    private static URI normalizeResultUri(URI uri) {
        String host = uri.getHost();
        if (host == null || !host.toLowerCase().endsWith(".aliyuncs.com")) {
            throw new CliException(ErrorCode.UNKNOWN, "百炼返回了不受信任的语音地址。");
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return uri;
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            throw new CliException(ErrorCode.UNKNOWN, "百炼返回了不受信任的语音地址。");
        }
        String rawUrl = uri.toString();
        // OSS 临时签名依赖原始 path/query 字节。这里只替换 scheme，绝不重新编码查询参数。
        return URI.create("https" + rawUrl.substring("http".length()));
    }

    record BailianApiError(
            String code,
            String message,
            String requestId) {
    }
}
