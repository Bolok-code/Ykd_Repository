package com.clitoolbox.ai.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.clitoolbox.config.IntentAiConfig;
import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import com.clitoolbox.intent.IntentContext;
import com.clitoolbox.intent.IntentDecision;
import com.clitoolbox.intent.IntentType;
import com.clitoolbox.intent.ReplyMode;
import com.clitoolbox.weather.WeatherService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.client.RestClient;

class SpringAiDoubaoIntentClassifierTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;
    private SpringAiDoubaoIntentClassifier classifier;

    @BeforeEach
    void setUp() throws IOException {
        responseBody.set(chatCompletion("""
                {
                  "intent": "WEATHER_QUERY",
                  "replyMode": "VOICE",
                  "confidence": 0.98,
                  "city": "杭州",
                  "targetDate": "2026-07-21",
                  "requestText": "明天杭州天气"
                }
                """));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", this::handleRequest);
        server.start();

        IntentAiConfig config = new IntentAiConfig(
                "test-ark-key",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "doubao-seed-2-0-mini-260428",
                350,
                Duration.ofSeconds(5),
                0.70);
        ChatModel chatModel = SpringAiDoubaoIntentClassifier.createChatModel(
                config,
                RestClient.builder());
        classifier = new SpringAiDoubaoIntentClassifier(
                config,
                chatModel,
                objectMapper,
                Clock.fixed(
                        Instant.parse("2026-07-20T02:00:00Z"),
                        WeatherService.WEATHER_ZONE));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendsSpringAiRequestAndParsesStructuredDecision() throws Exception {
        IntentDecision decision =
                classifier.classify("请用语音回答：明天杭州天气");

        assertEquals(IntentType.WEATHER_QUERY, decision.intent());
        assertEquals(ReplyMode.VOICE, decision.replyMode());
        assertEquals("杭州", decision.city());
        assertEquals(LocalDate.of(2026, 7, 21), decision.targetDate());
        assertEquals("明天杭州天气", decision.requestText());
        assertEquals("Bearer test-ark-key", authorization.get());

        JsonNode request = objectMapper.readTree(requestBody.get());
        assertEquals("doubao-seed-2-0-mini-260428", request.path("model").asText());
        assertEquals(350, request.path("max_tokens").asInt());
        assertEquals(0.0, request.path("temperature").asDouble());
        assertEquals("disabled", request.path("thinking").path("type").asText());
        assertEquals("system", request.path("messages").path(0).path("role").asText());
        assertTrue(
                request.path("messages").path(0).path("content").asText()
                        .contains("今天天气真好"));
        assertTrue(
                request.path("messages").path(1).path("content").asText()
                        .contains("2026-07-20"));
    }

    @Test
    void sendsPreviousWeatherContextWithAmbiguousFollowUp() throws Exception {
        IntentContext context = new IntentContext(
                "WEATHER_QUERY",
                "Xuzhou",
                LocalDate.of(2026, 7, 21));

        classifier.classify("the day after tomorrow?", context);

        JsonNode request = objectMapper.readTree(requestBody.get());
        String userMessage = request.path("messages").path(1).path("content").asText();
        assertTrue(userMessage.contains("\"currentMessage\":\"the day after tomorrow?\""));
        assertTrue(userMessage.contains("\"previousIntent\":\"WEATHER_QUERY\""));
        assertTrue(userMessage.contains("\"city\":\"Xuzhou\""));
        assertTrue(userMessage.contains("\"targetDate\":\"2026-07-21\""));
    }

    @Test
    void lowConfidenceDecisionFallsBackToOriginalTextChat() {
        responseBody.set(chatCompletion("""
                {
                  "intent": "WEATHER_QUERY",
                  "replyMode": "VOICE",
                  "confidence": 0.52,
                  "city": "杭州",
                  "targetDate": "2026-07-20",
                  "requestText": "杭州天气"
                }
                """));

        IntentDecision decision = classifier.classify("今天去杭州玩了，天气真好");

        assertEquals(IntentType.TEXT_CHAT, decision.intent());
        assertEquals(ReplyMode.TEXT, decision.replyMode());
        assertEquals("今天去杭州玩了，天气真好", decision.requestText());
    }

    @Test
    void acceptsJsonInsideMarkdownFence() {
        responseBody.set(chatCompletion("""
                ```json
                {
                  "intent": "IMAGE_GENERATION",
                  "replyMode": "TEXT",
                  "confidence": 0.95,
                  "city": "",
                  "targetDate": "",
                  "requestText": "夏日荷塘"
                }
                ```
                """));

        IntentDecision decision = classifier.classify("请画一幅夏日荷塘");

        assertEquals(IntentType.IMAGE_GENERATION, decision.intent());
        assertEquals("夏日荷塘", decision.requestText());
    }

    @Test
    void rejectsMalformedModelOutput() {
        responseBody.set(chatCompletion("这是一段普通文字"));

        CliException error = assertThrows(
                CliException.class,
                () -> classifier.classify("你好"));

        assertEquals(ErrorCode.UNKNOWN, error.getErrorCode());
    }

    @Test
    void mapsUnauthorizedResponseToConfigurationError() {
        responseStatus.set(401);
        responseBody.set("""
                {"error":{"message":"Authentication failed","code":"invalid_key"}}
                """);

        CliException error = assertThrows(
                CliException.class,
                () -> classifier.classify("你好"));

        assertEquals(ErrorCode.CONFIG_ERROR, error.getErrorCode());
    }

    @Test
    void mapsModelNotOpenResponseToConfigurationError() {
        responseStatus.set(404);
        responseBody.set("""
                {
                  "error": {
                    "message": "The model has not been activated",
                    "code": "ModelNotOpen"
                  }
                }
                """);

        CliException error = assertThrows(
                CliException.class,
                () -> classifier.classify("你好"));

        assertEquals(ErrorCode.CONFIG_ERROR, error.getErrorCode());
        assertTrue(error.getUserMessage().contains("尚未"));
    }

    private String chatCompletion(String content) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "id", "chatcmpl-intent-test",
                    "object", "chat.completion",
                    "created", 1784512800,
                    "model", "doubao-seed-2-0-mini-260428",
                    "choices", List.of(Map.of(
                            "index", 0,
                            "message", Map.of(
                                    "role", "assistant",
                                    "content", content),
                            "finish_reason", "stop")),
                    "usage", Map.of(
                            "prompt_tokens", 20,
                            "completion_tokens", 20,
                            "total_tokens", 40)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(responseStatus.get(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
