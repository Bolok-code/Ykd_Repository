package com.clitoolbox.ai.vision;

import com.clitoolbox.config.BailianConfig;
import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.MimeType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 通过 Spring AI 的 OpenAI 兼容层调用阿里云百炼视觉模型。
 */
public final class SpringAiBailianVisionClient implements ImageUnderstandingClient {
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final String DEFAULT_QUESTION =
            "请识别这张图片并帮助用户解决图片中的问题。"
                    + "如果图片包含题目，请给出清晰的分析和解题步骤；"
                    + "如果不是题目，请准确描述图片中的关键信息。";

    private final BailianConfig config;
    private final ChatModel chatModel;

    public SpringAiBailianVisionClient(BailianConfig config, ChatModel chatModel) {
        this.config = config;
        this.chatModel = chatModel;
    }

    @Override
    public String analyze(byte[] imageBytes, String mimeType, String question) {
        validateImage(imageBytes, mimeType);
        String promptText = question == null || question.isBlank()
                ? DEFAULT_QUESTION
                : question.trim();
        ByteArrayResource imageResource = new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return "wechat-image";
            }
        };
        UserMessage userMessage = UserMessage.builder()
                .text(promptText)
                .media(new Media(MimeType.valueOf(mimeType), imageResource))
                .build();

        try {
            ChatResponse response = chatModel.call(new Prompt(List.of(userMessage)));
            return extractAnswer(response);
        } catch (CliException e) {
            throw e;
        } catch (NonTransientAiException e) {
            throw new CliException(
                    ErrorCode.CONFIG_ERROR,
                    "百炼视觉模型请求被拒绝，请检查 API Key、业务空间、地域和模型权限。",
                    e);
        } catch (TransientAiException | ResourceAccessException e) {
            throw new CliException(
                    ErrorCode.NETWORK_ERROR,
                    "百炼视觉模型暂时不可用，请稍后重试。",
                    e);
        } catch (RuntimeException e) {
            throw new CliException(
                    ErrorCode.NETWORK_ERROR,
                    "无法调用百炼视觉模型，请检查网络和百炼配置。",
                    e);
        }
    }

    @Override
    public String modelName() {
        return config.visionModel();
    }

    public static ChatModel createChatModel(
            BailianConfig config,
            RestClient.Builder restClientBuilder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(config.timeout())
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(config.timeout());

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(config.compatibleBaseUrl().toString())
                .apiKey(config.apiKey())
                .completionsPath("/chat/completions")
                .restClientBuilder(restClientBuilder.clone().requestFactory(requestFactory))
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.visionModel())
                .maxTokens(2_000)
                .extraBody(Map.of("enable_thinking", false))
                .build();
        RetryTemplate retryTemplate = RetryTemplate.builder()
                .maxAttempts(3)
                .retryOn(TransientAiException.class)
                .retryOn(ResourceAccessException.class)
                .exponentialBackoff(
                        Duration.ofMillis(500),
                        2.0,
                        Duration.ofSeconds(3))
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .retryTemplate(retryTemplate)
                .build();
    }

    private void validateImage(byte[] imageBytes, String mimeType) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new CliException(ErrorCode.INVALID_INPUT, "收到的图片内容为空。");
        }
        if (imageBytes.length > MAX_IMAGE_BYTES) {
            throw new CliException(ErrorCode.INVALID_INPUT, "图片超过 20MB，暂时无法识别。");
        }
        if (mimeType == null || !mimeType.startsWith("image/")) {
            throw new CliException(ErrorCode.INVALID_INPUT, "收到的文件不是受支持的图片格式。");
        }
    }

    private String extractAnswer(ChatResponse response) {
        Generation generation = response == null ? null : response.getResult();
        String answer = generation == null || generation.getOutput() == null
                ? null
                : generation.getOutput().getText();
        if (answer == null || answer.isBlank()) {
            throw new CliException(ErrorCode.UNKNOWN, "百炼视觉模型返回了空结果。");
        }
        return answer.trim();
    }
}
