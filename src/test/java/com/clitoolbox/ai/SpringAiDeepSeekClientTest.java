package com.clitoolbox.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.clitoolbox.config.DeepSeekConfig;
import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpringAiDeepSeekClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;
    private SpringAiDeepSeekClient client;

    @BeforeEach
    void setUp() throws IOException {
        responseBody.set(chatCompletion("你好，我是 DeepSeek 助手。", "stop"));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", this::handleRequest);
        server.start();

        URI baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        DeepSeekConfig config = new DeepSeekConfig(
                "test-api-key",
                baseUrl,
                "deepseek-v4-flash",
                1_000,
                Duration.ofSeconds(5),
                false,
                10);
        client = new SpringAiDeepSeekClient(config);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendsExpectedSpringAiRequestAndParsesAnswer() throws Exception {
        String answer = client.chat(List.of(
                ChatMessage.system("你是助手"),
                ChatMessage.user("你好")));

        assertEquals("你好，我是 DeepSeek 助手。", answer);
        assertEquals("Bearer test-api-key", authorization.get());

        JsonNode json = objectMapper.readTree(requestBody.get());
        assertEquals("deepseek-v4-flash", json.get("model").asText());
        assertEquals(false, json.get("stream").asBoolean());
        assertEquals(1_000, json.get("max_tokens").asInt());
        assertEquals("disabled", json.get("thinking").get("type").asText());
        assertEquals("system", json.get("messages").get(0).get("role").asText());
        assertEquals("你好", json.get("messages").get(1).get("content").asText());
    }

    @Test
    void mapsUnauthorizedResponseToConfigurationError() {
        responseStatus.set(401);
        responseBody.set("""
                {"error":{"message":"Authentication failed","type":"auth","code":"invalid_key"}}
                """);

        CliException error = assertThrows(
                CliException.class,
                () -> client.chat(List.of(ChatMessage.user("你好"))));

        assertEquals(ErrorCode.CONFIG_ERROR, error.getErrorCode());
        assertTrue(error.getUserMessage().contains("API Key"));
    }

    @Test
    void rejectsEmptyModelAnswer() {
        responseBody.set(chatCompletion("", "stop"));

        CliException error = assertThrows(
                CliException.class,
                () -> client.chat(List.of(ChatMessage.user("你好"))));

        assertEquals(ErrorCode.UNKNOWN, error.getErrorCode());
    }

    @Test
    void marksLengthLimitedAnswerAsIncomplete() {
        responseBody.set(chatCompletion("部分回答", "length"));

        String answer = client.chat(List.of(ChatMessage.user("请详细回答")));

        assertTrue(answer.startsWith("部分回答"));
        assertTrue(answer.contains("内容可能不完整"));
    }

    @Test
    void mapsContentFilterBeforeCheckingEmptyContent() {
        responseBody.set(chatCompletion(null, "content_filter"));

        CliException error = assertThrows(
                CliException.class,
                () -> client.chat(List.of(ChatMessage.user("测试"))));

        assertEquals(ErrorCode.INVALID_INPUT, error.getErrorCode());
        assertTrue(error.getUserMessage().contains("内容安全"));
    }

    private String chatCompletion(String content, String finishReason) {
        String contentJson = content == null
                ? "null"
                : "\"" + content.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        return """
                {
                  "id": "chatcmpl-test",
                  "object": "chat.completion",
                  "created": 1784246400,
                  "model": "deepseek-v4-flash",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": %s
                      },
                      "finish_reason": "%s"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 2,
                    "completion_tokens": 2,
                    "total_tokens": 4
                  }
                }
                """.formatted(contentJson, finishReason);
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
