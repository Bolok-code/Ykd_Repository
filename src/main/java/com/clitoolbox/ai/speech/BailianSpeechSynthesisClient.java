package com.clitoolbox.ai.speech;

import com.clitoolbox.config.BailianConfig;
import com.clitoolbox.config.BailianTtsConfig;
import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 调用百炼 Qwen-Audio-TTS 非实时接口，将文字合成为 16 位单声道 PCM。
 */
public final class BailianSpeechSynthesisClient implements SpeechSynthesisClient {
    static final int PCM_ENCODE_TYPE = 1;
    static final int PCM_BITS_PER_SAMPLE = 16;
    private static final int PCM_CHANNELS = 1;
    private static final int MAX_TEXT_CHARS = 5_000;

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
            JsonNode response = synthesisClient.post()
                    .uri(synthesisUri)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            URI audioUri = extractAudioUri(response);
            return downloadGeneratedSpeech(audioUri);
        } catch (CliException e) {
            throw e;
        } catch (RestClientResponseException e) {
            ErrorCode code = e.getStatusCode().value() == 401
                    ? ErrorCode.CONFIG_ERROR
                    : ErrorCode.NETWORK_ERROR;
            throw new CliException(
                    code,
                    "百炼语音合成请求失败，请检查 API Key、业务空间、地域、模型和音色权限。",
                    e);
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
        if (byteLength <= 0 || sampleRate <= 0) {
            throw new CliException(ErrorCode.UNKNOWN, "百炼返回的 PCM 音频信息不完整。");
        }
        long bytesPerSecond =
                (long) sampleRate * PCM_CHANNELS * PCM_BITS_PER_SAMPLE / 8;
        long playTimeMs = Math.max(1L, byteLength * 1_000L / bytesPerSecond);
        return Math.toIntExact(playTimeMs);
    }

    private GeneratedSpeech downloadGeneratedSpeech(URI audioUri) {
        ResponseEntity<byte[]> entity = downloadClient.get()
                .uri(audioUri)
                .retrieve()
                .toEntity(byte[].class);
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
                PCM_BITS_PER_SAMPLE);
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
        try {
            return new URI(
                    "https",
                    uri.getUserInfo(),
                    uri.getHost(),
                    uri.getPort(),
                    uri.getPath(),
                    uri.getQuery(),
                    uri.getFragment());
        } catch (URISyntaxException e) {
            throw new CliException(ErrorCode.UNKNOWN, "百炼返回的语音地址格式不正确。", e);
        }
    }
}
