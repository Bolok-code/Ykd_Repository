package com.clitoolbox.ai.image;

import com.clitoolbox.config.BailianConfig;
import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 调用 Qwen-Image 同步图像生成接口，并立即下载返回的临时图片。
 */
public final class BailianImageGenerationClient implements ImageGenerationClient {
    private static final int MAX_PROMPT_CHARS = 5_000;

    private final BailianConfig config;
    private final RestClient generationClient;
    private final RestClient downloadClient;
    private final URI generationUri;

    public BailianImageGenerationClient(
            BailianConfig config,
            RestClient generationClient,
            RestClient downloadClient) {
        this.config = config;
        this.generationClient = generationClient;
        this.downloadClient = downloadClient;
        this.generationUri = URI.create(
                config.dashScopeBaseUrl()
                        + "/services/aigc/multimodal-generation/generation");
    }

    public static BailianImageGenerationClient create(
            BailianConfig config,
            RestClient.Builder restClientBuilder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(config.timeout())
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(config.timeout());
        RestClient generationClient = restClientBuilder.clone()
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        RestClient downloadClient = restClientBuilder.clone()
                .requestFactory(requestFactory)
                .build();
        return new BailianImageGenerationClient(
                config,
                generationClient,
                downloadClient);
    }

    @Override
    public GeneratedImage generate(String prompt) {
        String normalizedPrompt = validatePrompt(prompt);
        Map<String, Object> body = Map.of(
                "model", config.imageModel(),
                "input", Map.of(
                        "messages", List.of(Map.of(
                                "role", "user",
                                "content", List.of(Map.of("text", normalizedPrompt))))),
                "parameters", Map.of(
                        "size", config.imageSize(),
                        "n", 1,
                        "prompt_extend", true,
                        "watermark", false));

        try {
            JsonNode response = generationClient.post()
                    .uri(generationUri)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            URI imageUri = extractImageUri(response);
            return downloadGeneratedImage(imageUri);
        } catch (CliException e) {
            throw e;
        } catch (RestClientResponseException e) {
            ErrorCode code = e.getStatusCode().value() == 401
                    ? ErrorCode.CONFIG_ERROR
                    : ErrorCode.NETWORK_ERROR;
            throw new CliException(
                    code,
                    "百炼文生图请求失败，请检查 API Key、业务空间、地域、模型权限和余额。",
                    e);
        } catch (ResourceAccessException e) {
            throw new CliException(
                    ErrorCode.NETWORK_ERROR,
                    "百炼文生图请求超时或网络不可用，请稍后重试。",
                    e);
        } catch (RuntimeException e) {
            throw new CliException(
                    ErrorCode.NETWORK_ERROR,
                    "无法调用百炼文生图服务，请检查网络和百炼配置。",
                    e);
        }
    }

    @Override
    public String modelName() {
        return config.imageModel();
    }

    static URI extractImageUri(JsonNode response) {
        if (response == null) {
            throw new CliException(ErrorCode.UNKNOWN, "百炼文生图返回了空结果。");
        }
        JsonNode contents = response.path("output")
                .path("choices")
                .path(0)
                .path("message")
                .path("content");
        if (contents.isArray()) {
            for (JsonNode content : contents) {
                if (content.path("image").isTextual()) {
                    URI imageUri = URI.create(content.path("image").asText());
                    validateResultUri(imageUri);
                    return imageUri;
                }
            }
        }
        String message = response.path("message").asText();
        throw new CliException(
                ErrorCode.UNKNOWN,
                message.isBlank() ? "百炼文生图没有返回图片。" : "百炼文生图失败：" + message);
    }

    private GeneratedImage downloadGeneratedImage(URI imageUri) {
        ResponseEntity<byte[]> entity = downloadClient.get()
                .uri(imageUri)
                .retrieve()
                .toEntity(byte[].class);
        byte[] bytes = entity.getBody();
        if (bytes == null || bytes.length == 0) {
            throw new CliException(ErrorCode.UNKNOWN, "百炼生成的图片下载失败。");
        }
        MediaType contentType = entity.getHeaders().getContentType();
        String mimeType = contentType == null
                ? MediaType.IMAGE_PNG_VALUE
                : contentType.toString();
        String extension = mimeType.contains("jpeg") || mimeType.contains("jpg")
                ? ".jpg"
                : ".png";
        return new GeneratedImage(bytes, "bailian-generated" + extension, mimeType);
    }

    private String validatePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new CliException(ErrorCode.INVALID_INPUT, "文生图描述不能为空。");
        }
        String normalized = prompt.trim();
        if (normalized.length() > MAX_PROMPT_CHARS) {
            throw new CliException(ErrorCode.INVALID_INPUT, "文生图描述不能超过 5000 个字符。");
        }
        return normalized;
    }

    private static void validateResultUri(URI uri) {
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || host == null
                || !host.toLowerCase().endsWith(".aliyuncs.com")) {
            throw new CliException(ErrorCode.UNKNOWN, "百炼返回了不受信任的图片地址。");
        }
    }
}
