package com.example.wechatbot.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class AiChatService {
    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);
    @Autowired
    private ConversationManager conversationManager;
    @Value("${app.ai.deepseek-key:}")
    private String deepseekKey;
    @Value("${app.ai.bailian-key:}")
    private String bailianKey;
    private static final String DEEPSEEK_URL = "https://api.deepseek.com/chat/completions";
    private static final String WS_URL = "https://ws-m8c84ets3r979umj.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String TEXT_MODEL = "deepseek-chat";
    private static final String IMG_MODEL = "qwen-image-2.0";
    private static final String TTS_MODEL = "cosyvoice-v3-flash";
    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile boolean enabled = false;
    private static final Pattern[] IMAGE_PATTERNS = {
        Pattern.compile("[\\u7ed8\\u753b\\u753b]", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(generate|create|draw|paint)\\s+(a|an|the|some|me)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("[\\u751f\\u6210][\\u4e00-\\u9fa5]{0,10}[\\u56fe\\u7247\\u56fe\\u50cf\\u7ed8\\u753b]", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(\\u753b\\u4e00\\u5f20|\\u751f\\u6210\\u4e00\\u5f20)", Pattern.CASE_INSENSITIVE)
    };
    @PostConstruct
    public void init() {
        enabled = deepseekKey != null && !deepseekKey.isEmpty()
            && bailianKey != null && !bailianKey.isEmpty();
        log.info("AiChatService ready");
    }
    private boolean isImageRequest(String msg) {
        for (Pattern p : IMAGE_PATTERNS) { if (p.matcher(msg).find()) return true; }
        return false;
    }
    public String chat(String message) { return chat(message, null); }
    public String chat(String message, String userId) {
        if (!enabled) return "AI not configured";
        try {
            if (isImageRequest(message)) return callApi(message, WS_URL, IMG_MODEL, bailianKey);
            if (userId != null) return chatWithContext(message, userId);
            return callApi(message, DEEPSEEK_URL, TEXT_MODEL, deepseekKey);
        } catch (Exception e) { log.error("AI error", e); return "Error: " + e.getMessage(); }
    }
    private String chatWithContext(String message, String userId) {
        try {
            List<Map<String, String>> history = conversationManager.getContext(userId);
            ObjectNode body = mapper.createObjectNode(); body.put("model", TEXT_MODEL);
            ArrayNode msgs = body.putArray("messages");
            ObjectNode sys = msgs.addObject(); sys.put("role", "system"); sys.put("content", "你是一个有用的AI助手，请根据对话历史回答问题。");
            for (Map<String, String> h : history) {
                ObjectNode m = msgs.addObject(); m.put("role", h.get("role")); m.put("content", h.get("content"));
            }
            ObjectNode cur = msgs.addObject(); cur.put("role", "user"); cur.put("content", message);
            body.put("stream", false); body.put("max_tokens", 4000);
            Request req = new Request.Builder().url(DEEPSEEK_URL)
                .post(RequestBody.create(mapper.writeValueAsString(body), MediaType.parse("application/json")))
                .addHeader("Authorization", "Bearer " + deepseekKey).build();
            try (Response resp = http.newCall(req).execute()) {
                String json = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) { log.error("API {}: {}", resp.code(), json); return "Error: " + resp.code(); }
                JsonNode r = mapper.readTree(json); JsonNode c = r.path("choices");
                String reply = (c.isArray() && c.size() > 0) ? c.get(0).path("message").path("content").asText() : "";
                conversationManager.addMessage(userId, "user", message);
                conversationManager.addMessage(userId, "assistant", reply);
                return reply;
            }
        } catch (Exception e) { log.error("Chat context error", e); return "Error: " + e.getMessage(); }
    }
    public String analyzeImage(byte[] imgData) {
        try {
            String b64 = java.util.Base64.getEncoder().encodeToString(imgData);
            ObjectNode body = mapper.createObjectNode(); body.put("model", "qwen-vl-plus");
            ArrayNode msgs = body.putArray("messages"); ObjectNode u = msgs.addObject(); u.put("role", "user");
            ArrayNode content = u.putArray("content");
            ObjectNode tNode = content.addObject(); tNode.put("type", "text"); tNode.put("text", "请详细描述这张图片中的内容");
            ObjectNode img = content.addObject(); img.put("type", "image_url");
            ObjectNode iu = img.putObject("image_url"); iu.put("url", "data:image/jpeg;base64," + b64);
            body.put("stream", false); body.put("max_tokens", 2000);
            Request req = new Request.Builder().url(WS_URL)
                .post(RequestBody.create(mapper.writeValueAsString(body), MediaType.parse("application/json")))
                .addHeader("Authorization", "Bearer " + bailianKey).build();
            try (Response resp = http.newCall(req).execute()) {
                String json = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) return "识别失败";
                JsonNode r = mapper.readTree(json); JsonNode c = r.path("choices");
                if (c.isArray() && c.size() > 0) return c.get(0).path("message").path("content").asText();
                return json.length() > 200 ? json.substring(0, 200) : json;
            }
        } catch (Exception e) { log.error("VLM error", e); return "识别失败"; }
    }
    public String synthesizeSpeech(String text) {
        if (text == null || text.trim().isEmpty()) return "请提供要朗读的文本";
        try {
            SpeechSynthesisParam param = SpeechSynthesisParam.builder()
                .apiKey(bailianKey).model("cosyvoice-v3-flash").voice("longanyang").build();
            SpeechSynthesizer synthesizer = new SpeechSynthesizer(param, null);
            ByteBuffer audio = synthesizer.call(text.trim());
            if (audio == null || audio.remaining() == 0) return "语音生成失败";
            byte[] audioBytes = new byte[audio.remaining()]; audio.get(audioBytes);
            int durationSec = Math.max(1, audioBytes.length / 16000);
            String b64 = java.util.Base64.getEncoder().encodeToString(audioBytes);
            log.info("TTS成功: text={}, duration={}s", text.trim(), durationSec);
            return "VOICE:mp3," + durationSec + "," + b64;
        } catch (Exception e) { log.error("TTS error", e); return "语音生成失败"; }
    }
    private String sendAsImage(String url) {
        try {
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    return "IMG:" + java.util.Base64.getEncoder().encodeToString(resp.body().bytes());
                }
            }
        } catch (Exception e) { log.error("Download fail", e); }
        return "Image: " + url;
    }
    private String callApi(String msg, String url, String model, String key) throws Exception {
        ObjectNode body = mapper.createObjectNode(); body.put("model", model);
        ArrayNode msgs = body.putArray("messages"); ObjectNode u = msgs.addObject(); u.put("role", "user");
        if (model.equals(IMG_MODEL)) { ArrayNode ca = u.putArray("content"); ObjectNode t = ca.addObject(); t.put("text", msg); }
        else { u.put("content", msg); }
        body.put("stream", false); body.put("max_tokens", 4000);
        Request req = new Request.Builder().url(url)
            .post(RequestBody.create(mapper.writeValueAsString(body), MediaType.parse("application/json")))
            .addHeader("Authorization", "Bearer " + key).build();
        try (Response resp = http.newCall(req).execute()) {
            String json = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) { log.error("API {}: {}", resp.code(), json); return "Error: " + resp.code(); }
            JsonNode r = mapper.readTree(json); JsonNode c = r.path("choices");
            if (c.isArray() && c.size() > 0) return c.get(0).path("message").path("content").asText();
            JsonNode oc = r.path("output").path("choices");
            if (oc.isArray() && oc.size() > 0) {
                JsonNode ct = oc.get(0).path("message").path("content");
                if (ct.isArray() && ct.size() > 0) { String img = ct.get(0).path("image").asText(); if (!img.isEmpty()) return sendAsImage(img); return ct.get(0).path("text").asText(); }
                return ct.asText();
            }
            JsonNode results = r.path("output").path("results");
            if (results.isArray() && results.size() > 0) return results.get(0).path("url").asText();
            return json.length() > 200 ? json.substring(0, 200) : json;
        }
    }
}
