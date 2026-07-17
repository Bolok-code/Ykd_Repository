package ykd.ykd.ai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * DeepSeek API 客户端 —— 纯 HTTP 通信，不关心业务逻辑。
 *
 * <p>负责：拼请求体 → 发 HTTP POST → 解析响应 → 返回大模型的回答文本。</p>
 * <p>DeepSeek 使用 OpenAI 兼容的 API 格式。</p>
 */
@Component
public class DeepSeekClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    private final RestTemplate restTemplate;

    @Value("${deepseek.api.url}")
    private String apiUrl;

    @Value("${deepseek.api.key}")
    private String apiKey;

    @Value("${deepseek.api.model}")
    private String model;

    public DeepSeekClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 发送消息给 DeepSeek，获取大模型回答。
     *
     * @param systemPrompt 系统提示词（设定角色和行为）
     * @param userMessage  用户消息
     * @return 大模型的回答文本
     */
    public String chat(String systemPrompt, String userMessage) {
        // 构建请求体
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);

        JsonArray messages = new JsonArray();

        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", systemPrompt);
        messages.add(systemMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userMessage);
        messages.add(userMsg);

        body.add("messages", messages);

        // 构建请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        HttpEntity<String> request = new HttpEntity<>(body.toString(), headers);

        log.info("调用 DeepSeek API，模型: {}", model);
        log.debug("请求内容: {}", userMessage);

        try {
            String response = restTemplate.postForObject(apiUrl, request, String.class);

            if (response == null || response.isBlank()) {
                log.error("DeepSeek API 返回为空");
                return null;
            }

            return parseResponse(response);
        } catch (Exception e) {
            log.error("DeepSeek API 调用失败", e);
            return null;
        }
    }

    /**
     * 解析 DeepSeek API 响应，提取回答文本。
     *
     * <p>响应格式：
     * <pre>
     * {
     *   "choices": [
     *     {
     *       "message": {
     *         "content": "大模型的回答"
     *       }
     *     }
     *   ]
     * }
     * </pre>
     */
    private String parseResponse(String response) {
        try {
            JsonObject root = JsonParser.parseString(response).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");

            if (choices == null || choices.isEmpty()) {
                log.error("DeepSeek 响应中没有 choices");
                return null;
            }

            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            String content = message.get("content").getAsString();

            log.info("DeepSeek 回答长度: {}", content.length());
            return content;
        } catch (Exception e) {
            log.error("解析 DeepSeek 响应失败: {}", response, e);
            return null;
        }
    }
}