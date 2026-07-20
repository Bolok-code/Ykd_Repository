package com.clitoolbox.intent;

import java.time.LocalDate;

/**
 * 意图模型返回并经过本地校验后的结构化结果。
 *
 * @param intent 业务意图
 * @param replyMode 文字或语音回复
 * @param confidence 模型置信度，范围为 0 到 1
 * @param city 天气查询城市，其他意图为 {@code null}
 * @param targetDate 天气查询日期，其他意图为 {@code null}
 * @param requestText 去除“用语音回答”等包装语后的实际问题或生图提示词
 */
public record IntentDecision(
        IntentType intent,
        ReplyMode replyMode,
        double confidence,
        String city,
        LocalDate targetDate,
        String requestText) {

    public IntentDecision {
        if (intent == null) {
            throw new IllegalArgumentException("意图不能为空");
        }
        if (replyMode == null) {
            throw new IllegalArgumentException("回复形式不能为空");
        }
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("意图置信度必须在 0 到 1 之间");
        }
        if (requestText == null || requestText.isBlank()) {
            throw new IllegalArgumentException("实际请求内容不能为空");
        }
        city = normalizeNullable(city);
        requestText = requestText.trim();
    }

    public static IntentDecision textChat(String text) {
        return new IntentDecision(
                IntentType.TEXT_CHAT,
                ReplyMode.TEXT,
                1.0,
                null,
                null,
                text);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
