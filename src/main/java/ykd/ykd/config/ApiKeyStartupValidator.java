package ykd.ykd.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动时校验外部服务 API Key。
 *
 * <p>{@code application.yml} 中提供了 {@code changeme} 占位 key，真实 key 位于
 * {@code config/application-local.yml}。若 local 配置加载失败（如文件缺失/语法错误），
 * 系统会带着占位 key 启动，所有 AI 功能静默失败。</p>
 *
 * <p>本校验器在启动早期检测到仍为占位符的 key 时直接抛异常，让应用快速失败，
 * 而不是带病启动后让用户在聊天里撞见"AI 服务不可用"。</p>
 */
@Slf4j
@Component
public class ApiKeyStartupValidator implements ApplicationRunner {

    private static final String PLACEHOLDER = "changeme";

    /** 整体开关：某些部署只需部分服务时，可设 ykd.api-key-validation.enabled=false 关闭校验 */
    @Value("${ykd.api-key-validation.enabled:true}")
    private boolean validationEnabled;

    @Value("${spring.ai.openai.api-key:}")
    private String openaiApiKey;

    @Value("${spring.ai.openai.embedding.api-key:}")
    private String embeddingApiKey;

    @Value("${spring.ai.deepseek.api-key:}")
    private String deepseekApiKey;

    @Value("${spring.ai.elevenlabs.api-key:}")
    private String elevenlabsApiKey;

    @Value("${gaode.key:}")
    private String gaodeKey;

    @Override
    public void run(ApplicationArguments args) {
        if (!validationEnabled) {
            log.warn("[Config] API Key 校验已通过配置关闭（ykd.api-key-validation.enabled=false）");
            return;
        }

        Map<String, String> placeholders = new LinkedHashMap<>();
        collectMissing(openaiApiKey, "spring.ai.openai.api-key（对话模型）", placeholders);
        collectMissing(embeddingApiKey, "spring.ai.openai.embedding.api-key（Embedding）", placeholders);
        collectMissing(deepseekApiKey, "spring.ai.deepseek.api-key（DeepSeek）", placeholders);
        collectMissing(elevenlabsApiKey, "spring.ai.elevenlabs.api-key（语音合成）", placeholders);
        collectMissing(gaodeKey, "gaode.key（高德定位）", placeholders);

        if (placeholders.isEmpty()) {
            log.info("[Config] API Key 校验通过");
            return;
        }

        StringBuilder sb = new StringBuilder("检测到缺失或仍为占位符的 API Key，应用已停止启动：\n");
        placeholders.forEach((name, value) -> sb.append("  - ").append(name).append(" = ").append(value).append('\n'));
        sb.append("请将真实 Key 填入 config/application-local.yml（参考 application-local.yml.example），")
          .append("或确认 local 配置已正确加载；如确需部分服务运行，可设置 ykd.api-key-validation.enabled=false。");
        throw new IllegalStateException(sb.toString());
    }

    private void collectMissing(String value, String propertyName, Map<String, String> collector) {
        String display = value == null || value.isBlank() ? "(未配置)" : value.trim();
        if (value == null || value.isBlank() || PLACEHOLDER.equalsIgnoreCase(value.trim())) {
            collector.put(propertyName, display);
        }
    }
}
