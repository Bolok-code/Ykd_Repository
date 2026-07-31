package ykd.ykd.job.model;

import java.util.Locale;

/**
 * 简历投递方式。
 *
 * <h3>三种模式</h3>
 * <ul>
 *   <li><b>ONLINE</b>（在线简历）— 通过猎聘在线简历投递，速度最快</li>
 *   <li><b>ATTACHMENT</b>（附件简历）— 上传 PDF/Word 文件，需要提前上传文件</li>
 *   <li><b>AUTO</b>（自动选择）— 优先尝试在线简历，失败时降级为附件简历</li>
 * </ul>
 */
public enum ResumeDeliveryMode {
    ONLINE,
    ATTACHMENT,
    AUTO;

    /**
     * 从字符串解析投递方式，支持中英文。
     *
     * @param value 原始字符串，如 "在线"、"ATTACHMENT"、"自动选择"
     * @return 对应的枚举值
     * @throws IllegalArgumentException 无法识别时抛出
     */
    public static ResumeDeliveryMode parse(String value) {
        if (value == null || value.isBlank()) return ONLINE;
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ONLINE", "在线", "在线简历" -> ONLINE;
            case "ATTACHMENT", "附件", "附件简历", "PDF" -> ATTACHMENT;
            case "AUTO", "自动", "自动选择" -> AUTO;
            default -> throw new IllegalArgumentException(
                    "不支持的简历发送方式\"" + value + "\"，请使用 ONLINE、ATTACHMENT 或 AUTO");
        };
    }
}
