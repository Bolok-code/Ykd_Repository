package ykd.ykd.job.model;

import java.util.Locale;

public enum ResumeDeliveryMode {
    ONLINE,
    ATTACHMENT,
    AUTO;

    public static ResumeDeliveryMode parse(String value) {
        if (value == null || value.isBlank()) return ONLINE;
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ONLINE", "在线", "在线简历" -> ONLINE;
            case "ATTACHMENT", "附件", "附件简历", "PDF" -> ATTACHMENT;
            case "AUTO", "自动", "自动选择" -> AUTO;
            default -> throw new IllegalArgumentException(
                    "不支持的简历发送方式“" + value + "”，请使用 ONLINE、ATTACHMENT 或 AUTO");
        };
    }
}