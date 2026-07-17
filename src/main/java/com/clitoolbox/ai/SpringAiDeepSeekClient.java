package com.clitoolbox.ai;

import com.clitoolbox.config.DeepSeekConfig;
import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 使用 Spring AI 的 OpenAI 兼容实现访问 DeepSeek Chat Completions API。
 */
public final class SpringAiDeepSeekClient implements AiChatClient {
    private static final Pattern HTTP_STATUS_PATTERN =
            Pattern.compile("(?<!\\d)([45]\\d{2})\\s*-");

    private final DeepSeekConfig config;
    private final ChatModel chatModel;

    public SpringAiDeepSeekClient(DeepSeekConfig config) {
        this(config, createChatModel(config));
    }

    SpringAiDeepSeekClient(DeepSeekConfig config, ChatModel chatModel) {
        this.config = config;
        this.chatModel = chatModel;
    }

    @Override
    public String chat(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new CliException(ErrorCode.INVALID_INPUT, "发送给 DeepSeek 的消息不能为空。");
        }

        try {
            ChatResponse response = chatModel.call(new Prompt(toSpringMessages(messages)));
            return extractAnswer(response);
        } catch (CliException e) {
            throw e;
        } catch (NonTransientAiException e) {
            throw mapHttpError(e);
        } catch (TransientAiException | ResourceAccessException e) {
            throw new CliException(
                    ErrorCode.NETWORK_ERROR,
                    "DeepSeek 服务暂时不可用，请稍后重试。",
                    e);
        } catch (RuntimeException e) {
            throw new CliException(
                    ErrorCode.NETWORK_ERROR,
                    "无法连接 DeepSeek，请检查网络后重试。",
                    e);
        }
    }

    @Override
    public String modelName() {
        return config.model();
    }

    static ChatModel createChatModel(DeepSeekConfig config) {
        return createChatModel(config, RestClient.builder());
    }

    static ChatModel createChatModel(
            DeepSeekConfig config,
            RestClient.Builder restClientBuilder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(config.timeout())
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(config.timeout());

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(config.baseUrl().toString())
                .apiKey(config.apiKey())
                .completionsPath("/chat/completions")
                .restClientBuilder(restClientBuilder.requestFactory(requestFactory))
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.model())
                .maxTokens(config.maxTokens())
                .extraBody(Map.of(
                        "thinking",
                        Map.of("type", config.thinkingEnabled() ? "enabled" : "disabled")))
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

    private List<Message> toSpringMessages(List<ChatMessage> messages) {
        List<Message> converted = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            if (message == null) {
                throw new CliException(ErrorCode.INVALID_INPUT, "聊天消息列表不能包含空元素。");
            }
            converted.add(switch (message.role()) {
                case "system" -> new SystemMessage(message.content());
                case "user" -> new UserMessage(message.content());
                case "assistant" -> new AssistantMessage(message.content());
                default -> throw new CliException(
                        ErrorCode.INVALID_INPUT,
                        "不支持的消息角色: " + message.role());
            });
        }
        return converted;
    }

    private String extractAnswer(ChatResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            throw emptyAnswer();
        }

        Generation generation = response.getResult();
        if (generation == null) {
            throw emptyAnswer();
        }
        String finishReason = generation.getMetadata() == null
                ? null
                : generation.getMetadata().getFinishReason();
        String normalizedFinishReason = finishReason == null || finishReason.isBlank()
                ? "stop"
                : finishReason.toLowerCase(Locale.ROOT);

        if ("content_filter".equals(normalizedFinishReason)) {
            throw new CliException(
                    ErrorCode.INVALID_INPUT,
                    "该请求未通过内容安全检查，请调整问题后重试。");
        }
        if ("insufficient_system_resource".equals(normalizedFinishReason)) {
            throw new CliException(
                    ErrorCode.NETWORK_ERROR,
                    "DeepSeek 当前资源不足，请稍后重试。");
        }

        String content = generation.getOutput() == null
                ? null
                : generation.getOutput().getText();
        if (content == null || content.isBlank()) {
            throw emptyAnswer();
        }
        String answer = content.trim();

        return switch (normalizedFinishReason) {
            case "stop" -> answer;
            case "length" -> answer + "\n\n（回答已达到长度上限，内容可能不完整。）";
            default -> throw new CliException(
                    ErrorCode.UNKNOWN,
                    "DeepSeek 未正常完成回答，请稍后重试。");
        };
    }

    private CliException mapHttpError(NonTransientAiException error) {
        int statusCode = extractHttpStatus(error.getMessage());
        return switch (statusCode) {
            case 400 -> new CliException(
                    ErrorCode.INVALID_INPUT,
                    "DeepSeek 无法处理当前请求，请调整问题后重试。",
                    error);
            case 401 -> new CliException(
                    ErrorCode.CONFIG_ERROR,
                    "DeepSeek API Key 无效。",
                    error);
            case 402 -> new CliException(
                    ErrorCode.CONFIG_ERROR,
                    "DeepSeek API 余额不足。",
                    error);
            case 429 -> new CliException(
                    ErrorCode.NETWORK_ERROR,
                    "DeepSeek 请求过于频繁，请稍后重试。",
                    error);
            default -> new CliException(
                    ErrorCode.UNKNOWN,
                    "DeepSeek 请求失败，请稍后重试。",
                    error);
        };
    }

    private int extractHttpStatus(String message) {
        if (message == null) {
            return -1;
        }
        Matcher matcher = HTTP_STATUS_PATTERN.matcher(message);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private CliException emptyAnswer() {
        return new CliException(ErrorCode.UNKNOWN, "DeepSeek 返回了空回答，请稍后重试。");
    }
}
