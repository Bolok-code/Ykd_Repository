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
 * 调用百炼 Qwen-Audio-TTS 非实时接口，将文字合成为 MP3 语音。
 */
public final class BailianSpeechSynthesisClient implements SpeechSynthesisClient {
    static final int MP3_ENCODE_TYPE = 7;
    static final int AUDIO_BITS_PER_SAMPLE = 16;
    private static final int MAX_TEXT_CHARS = 5_000;
    private static final int[] MPEG_1_LAYER_3_BITRATES =
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0};
    private static final int[] MPEG_2_LAYER_3_BITRATES =
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0};
    private static final int[] MPEG_1_SAMPLE_RATES = {44_100, 48_000, 32_000};

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
                        "format", "mp3",
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

    static int calculateMp3PlayTimeMs(byte[] audio) {
        if (audio == null || audio.length < 4) {
            throw new CliException(ErrorCode.UNKNOWN, "百炼返回的 MP3 音频信息不完整。");
        }

        int position = skipId3v2Tag(audio);
        long durationMicros = 0L;
        int frameCount = 0;
        while (position + 4 <= audio.length) {
            Mp3Frame frame = parseMp3Frame(audio, position);
            if (frame == null) {
                position++;
                continue;
            }
            if (position + frame.length() > audio.length) {
                break;
            }
            durationMicros +=
                    frame.samplesPerFrame() * 1_000_000L / frame.sampleRate();
            frameCount++;
            position += frame.length();
        }
        if (frameCount == 0) {
            throw new CliException(ErrorCode.UNKNOWN, "无法解析百炼返回的 MP3 音频时长。");
        }
        return Math.toIntExact(Math.max(1L, durationMicros / 1_000L));
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
        int playTimeMs = calculateMp3PlayTimeMs(bytes);
        return new GeneratedSpeech(
                bytes,
                "bailian-reply.mp3",
                playTimeMs,
                config.sampleRate(),
                MP3_ENCODE_TYPE,
                AUDIO_BITS_PER_SAMPLE);
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

    private static int skipId3v2Tag(byte[] audio) {
        if (audio.length < 10
                || audio[0] != 'I'
                || audio[1] != 'D'
                || audio[2] != '3') {
            return 0;
        }
        int size = ((audio[6] & 0x7F) << 21)
                | ((audio[7] & 0x7F) << 14)
                | ((audio[8] & 0x7F) << 7)
                | (audio[9] & 0x7F);
        int footerLength = (audio[5] & 0x10) == 0 ? 0 : 10;
        return Math.min(audio.length, 10 + size + footerLength);
    }

    private static Mp3Frame parseMp3Frame(byte[] audio, int position) {
        int header = ((audio[position] & 0xFF) << 24)
                | ((audio[position + 1] & 0xFF) << 16)
                | ((audio[position + 2] & 0xFF) << 8)
                | (audio[position + 3] & 0xFF);
        if ((header & 0xFFE00000) != 0xFFE00000) {
            return null;
        }

        int versionBits = (header >>> 19) & 0x3;
        int layerBits = (header >>> 17) & 0x3;
        int bitrateIndex = (header >>> 12) & 0xF;
        int sampleRateIndex = (header >>> 10) & 0x3;
        int padding = (header >>> 9) & 0x1;
        if (versionBits == 1
                || layerBits != 1
                || bitrateIndex == 0
                || bitrateIndex == 15
                || sampleRateIndex == 3) {
            return null;
        }

        boolean mpeg1 = versionBits == 3;
        int bitrateKbps = (mpeg1
                ? MPEG_1_LAYER_3_BITRATES
                : MPEG_2_LAYER_3_BITRATES)[bitrateIndex];
        int sampleRate = MPEG_1_SAMPLE_RATES[sampleRateIndex];
        if (versionBits == 2) {
            sampleRate /= 2;
        } else if (versionBits == 0) {
            sampleRate /= 4;
        }

        int coefficient = mpeg1 ? 144 : 72;
        int frameLength =
                coefficient * bitrateKbps * 1_000 / sampleRate + padding;
        int samplesPerFrame = mpeg1 ? 1_152 : 576;
        return frameLength > 4
                ? new Mp3Frame(frameLength, sampleRate, samplesPerFrame)
                : null;
    }

    private record Mp3Frame(
            int length,
            int sampleRate,
            int samplesPerFrame) {
    }
}
