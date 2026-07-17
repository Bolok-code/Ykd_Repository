package com.clitoolbox.config;

import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import java.net.URI;
import java.time.Duration;

/**
 * DeepSeek API 配置。敏感信息只从环境变量读取，不写入源码或配置文件。
 */
public record DeepSeekConfig(
        String apiKey,
        URI baseUrl,
        String model,
        int maxTokens,
        Duration timeout,
        boolean thinkingEnabled,
        int historyRounds) {

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    public static final String DEFAULT_MODEL = "deepseek-v4-flash";
    public static final int DEFAULT_MAX_TOKENS = 1_000;
    public static final int DEFAULT_TIMEOUT_SECONDS = 60;
    public static final int DEFAULT_HISTORY_ROUNDS = 10;

    public DeepSeekConfig {
        if (apiKey == null || apiKey.isBlank()) {
            throw new CliException(
                    ErrorCode.CONFIG_ERROR,
                    "DeepSeek API Key 未设置，请配置环境变量 DEEPSEEK_API_KEY。");
        }
        if (baseUrl == null || baseUrl.getScheme() == null || baseUrl.getHost() == null
                || (!"http".equalsIgnoreCase(baseUrl.getScheme())
                && !"https".equalsIgnoreCase(baseUrl.getScheme()))) {
            throw new CliException(ErrorCode.CONFIG_ERROR, "DEEPSEEK_BASE_URL 必须是有效的 HTTP(S) 地址。");
        }
        if (model == null || model.isBlank()) {
            throw new CliException(ErrorCode.CONFIG_ERROR, "DEEPSEEK_MODEL 不能为空。");
        }
        if (maxTokens < 1 || maxTokens > 32_768) {
            throw new CliException(ErrorCode.CONFIG_ERROR, "DEEPSEEK_MAX_TOKENS 必须在 1 到 32768 之间。");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new CliException(ErrorCode.CONFIG_ERROR, "DEEPSEEK_TIMEOUT_SECONDS 必须大于 0。");
        }
        if (historyRounds < 1 || historyRounds > 100) {
            throw new CliException(ErrorCode.CONFIG_ERROR, "DEEPSEEK_HISTORY_ROUNDS 必须在 1 到 100 之间。");
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
        return "DeepSeekConfig[apiKey=***, baseUrl=" + baseUrl
                + ", model=" + model
                + ", maxTokens=" + maxTokens
                + ", timeout=" + timeout
                + ", thinkingEnabled=" + thinkingEnabled
                + ", historyRounds=" + historyRounds + "]";
    }
}
