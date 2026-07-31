package ykd.ykd.llm.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.ykd.llm.tools.ReminderTools;
import ykd.ykd.memory.MemoryManagerService;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提醒请求拦截器。
 *
 * <p>用正则匹配用户消息中的提醒意图，直接调用 {@link ReminderTools#setReminder}
 * 而不经过 LLM。目的是避免 DeepSeek 模型在接收到提醒指令时跳过工具调用、
 * 直接编造"已设置提醒"的幻觉回复。</p>
 *
 * <h3>支持的时间表达式</h3>
 * <ul>
 *   <li>间隔重复：每5秒、每隔2小时、每30分钟</li>
 *   <li>单次延时：10分钟后、2小时后、30秒后</li>
 *   <li>每日定时：每天早上8点、每天08:30</li>
 *   <li>每周定时：每周日早上9点、每周一08:30</li>
 * </ul>
 *
 * <h3>不拦截的情况</h3>
 * <ul>
 *   <li>列表查询：查看提醒、还有哪些任务</li>
 *   <li>取消操作：取消提醒</li>
 *   <li>正则匹配不到时间表达式时</li>
 * </ul>
 * 这些请求仍然交给 LLM 处理，因为需要理解上下文和序号。
 */
@Slf4j
@Component
public class ReminderInterceptor {

    private final ReminderTools reminderTools;
    private final MemoryManagerService memoryManagerService;

    /** 间隔重复：每5秒 / 每隔2小时 / 每30分钟 */
    private static final Pattern INTERVAL_PATTERN =
            Pattern.compile("每\\s*(?:隔\\s*)?(\\d+)\\s*(秒|分钟?|小时)");

    /** 单次延时：10分钟后 / 2小时后 / 30秒后 */
    private static final Pattern ONCE_PATTERN =
            Pattern.compile("(\\d+)\\s*(秒|分钟?|小时)\\s*后");

    /** 每日定时：每天早上8点 / 每天08:30 */
    private static final Pattern DAILY_PATTERN =
            Pattern.compile("每天\\s*(\\d{1,2})[:：点](\\d{0,2})?");

    /** 每周定时：每周日早上9点 / 每周一08:30 */
    private static final Pattern WEEKLY_PATTERN =
            Pattern.compile("每[周週]([一二三四五六日天])\\s*(\\d{1,2})[:：点](\\d{0,2})?");

    public ReminderInterceptor(ReminderTools reminderTools,
                               MemoryManagerService memoryManagerService) {
        this.reminderTools = reminderTools;
        this.memoryManagerService = memoryManagerService;
    }

    /**
     * 尝试拦截提醒请求。
     *
     * @param text   用户消息文本
     * @param userId 微信用户 ID
     * @return 拦截成功返回提醒设置结果，否则返回 null 交由 LLM 处理
     */
    public String tryIntercept(String text, String userId) {
        if (text == null) return null;
        if (!looksLikeReminder(text)) return null;

        // 列表/取消类请求不拦截，交给 LLM 处理上下文
        if (text.contains("取消") || text.contains("查看") || text.contains("列表")
                || text.contains("还有哪些") || text.contains("有哪些")) {
            return null;
        }

        // 按优先级依次匹配四种时间表达式
        String timeExpr = extractTimeExpr(text);
        String message = extractMessage(text, timeExpr);
        if (timeExpr == null || message == null) return null;

        // 直接调用工具，绕过 LLM
        String result = reminderTools.setReminder(timeExpr, message);
        memoryManagerService.save(userId, text, result, "System");
        return result;
    }

    // ── 意图检测 ──────────────────────────────────────────────

    /**
     * 判断用户消息是否为提醒类请求。
     * 关键词：提醒、取消、任务；或以"每"开头且包含时间单位。
     * 排除：⏰ 定时提醒（已由 fireWithLLM 发出，避免循环）。
     */
    static boolean looksLikeReminder(String text) {
        if (text == null) return false;
        if (text.startsWith("⏰ 定时提醒")) return false;
        if (text.contains("提醒") || text.contains("取消") || text.contains("任务")) return true;
        return text.startsWith("每") && text.matches(".*[秒分钟时天].*");
    }

    // ── 时间表达式提取 ────────────────────────────────────────

    /**
     * 从用户消息中提取时间表达式。
     * 按优先级依次尝试：间隔 → 单次 → 每日 → 每周。
     * 统一转为 UnifiedReminderManager 能解析的格式。
     */
    private String extractTimeExpr(String text) {
        // 每5秒 / 每隔2小时 / 每30分钟 → "每5秒" / "每2小" / "每30分"
        Matcher m = INTERVAL_PATTERN.matcher(text);
        if (m.find()) return "每" + m.group(1) + m.group(2).charAt(0);

        // 10分钟后 / 2小时后 → "10分后" / "2小后"
        m = ONCE_PATTERN.matcher(text);
        if (m.find()) return m.group(1) + m.group(2).charAt(0) + "后";

        // 每天早上8点 / 每天08:30 → "每天8:00" / "每天8:30"
        m = DAILY_PATTERN.matcher(text);
        if (m.find()) return "每天" + m.group(1) + ":" + minOrDefault(m, 2);

        // 每周日早上9点 / 每周一08:30 → "每周日9:00" / "每周一8:30"
        m = WEEKLY_PATTERN.matcher(text);
        if (m.find()) return "每周" + m.group(1) + m.group(2) + ":" + minOrDefault(m, 3);

        return null;
    }

    /**
     * 从正则捕获组中取分钟值，没有则补 "00"。
     */
    private static String minOrDefault(Matcher m, int group) {
        String s = m.group(group);
        return (s != null && !s.isEmpty()) ? s : "00";
    }

    // ── 消息内容提取 ──────────────────────────────────────────

    /**
     * 去掉时间表达式后剩余的文本作为提醒消息。
     *
     * <p>例如 "每5秒给我发送你好" → 时间="每5秒"，消息="发送你好"。
     * 会剥离"给我"、"一条消息"、"提醒"等口语化前缀。</p>
     */
    private String extractMessage(String text, String timeExpr) {
        if (timeExpr == null) return null;

        // 定位时间表达式在原文中的位置
        int idx = text.indexOf(timeExpr);
        if (idx < 0) {
            // 正则提取的格式可能与原文不完全一致（如 "每5秒" vs "每五秒"），
            // 去掉"每"和"后"后用原文搜索
            String cn = timeExpr.replace("每", "").replace("后", "");
            idx = text.indexOf(cn);
        }
        if (idx >= 0) {
            String after = text.substring(idx)
                    .replaceFirst(Pattern.quote(timeExpr), "").trim();
            if (after.length() > 1) {
                // 剥离常见的口语化前缀
                after = after.replaceAll("^[给帮替]?[我你]\\s*", "");
                after = after.replaceAll("^[做发]", "");
                after = after.replaceAll("^一条消息\\s*", "");
                after = after.replaceAll("^提醒\\s*", "");
            }
            if (!after.isBlank()) return after;
        }

        // 兜底：整段文本去掉时间表达式后剩下的
        return text.replace(timeExpr, "").replaceAll("^[给帮替]?[我你]\\s*", "").trim();
    }
}
