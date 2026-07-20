package com.clitoolbox.intent;

import java.time.LocalDate;
import java.util.Locale;

/**
 * 由应用提供给意图模型的最近业务上下文，只用于帮助理解省略城市等追问。
 */
public record IntentContext(
        String previousIntent,
        String city,
        LocalDate targetDate) {

    public IntentContext {
        previousIntent = normalizeIntent(previousIntent);
        city = normalizeNullable(city);
    }

    private static String normalizeIntent(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
