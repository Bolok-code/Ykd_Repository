package com.clitoolbox.config;

import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import java.net.URI;
import java.time.Duration;

/**
 * 豆包意图识别模型的已校验配置。
 */
public record IntentAiConfig(
        String apiKey,
        URI baseUrl,
        String model,
        int maxTokens,
        Duration timeout,
        double confidenceThreshold) {

    public static final String DEFAULT_BASE_URL =
            "https://ark.cn-beijing.volces.com/api/v3";
    public static final String DEFAULT_MODEL = "doubao-seed-2-0-mini-260428";
    public static final int DEFAULT_MAX_TOKENS = 350;
    public static final int DEFAULT_TIMEOUT_SECONDS = 10;
    public static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.70;

    public IntentAiConfig {
        if (apiKey == null || apiKey.isBlank()) {
            throw new CliException(
                    ErrorCode.CONFIG_ERROR,
                    "豆包意图模型 API Key 未配置，请设置 ARK_API_KEY，"
                            + "或在 config/application-local.yml 中填写 app.intent-ai.api-key。");
        }
        if (baseUrl == null || baseUrl.getScheme() == null || baseUrl.getHost() == null
                || (!"http".equalsIgnoreCase(baseUrl.getScheme())
                && !"https".equalsIgnoreCase(baseUrl.getScheme()))) {
            throw new CliException(
                    ErrorCode.CONFIG_ERROR,
                    "ARK_BASE_URL 必须是有效的 HTTP(S) 地址。");
        }
        if (model == null || model.isBlank()) {
            throw new CliException(ErrorCode.CONFIG_ERROR, "ARK_INTENT_MODEL 不能为空。");
        }
        if (maxTokens < 100 || maxTokens > 2_000) {
            throw new CliException(
                    ErrorCode.CONFIG_ERROR,
                    "ARK_INTENT_MAX_TOKENS 必须在 100 到 2000 之间。");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new CliException(
                    ErrorCode.CONFIG_ERROR,
                    "ARK_INTENT_TIMEOUT_SECONDS 必须大于 0。");
        }
        if (!Double.isFinite(confidenceThreshold)
                || confidenceThreshold < 0.5
                || confidenceThreshold > 1.0) {
            throw new CliException(
                    ErrorCode.CONFIG_ERROR,
                    "INTENT_CONFIDENCE_THRESHOLD 必须在 0.5 到 1.0 之间。");
        }

        apiKey = apiKey.trim();
        baseUrl = normalizeBaseUrl(baseUrl);
        model = model.trim();
    }

    private static URI normalizeBaseUrl(URI baseUrl) {
        String value = baseUrl.toString();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return URI.create(value);
    }

    @Override
    public String toString() {
        return "IntentAiConfig[apiKey=***, baseUrl=" + baseUrl
                + ", model=" + model
                + ", maxTokens=" + maxTokens
                + ", timeout=" + timeout
                + ", confidenceThreshold=" + confidenceThreshold + "]";
    }
}
