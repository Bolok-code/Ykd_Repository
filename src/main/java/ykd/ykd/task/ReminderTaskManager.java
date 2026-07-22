package ykd.ykd.task;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import ykd.ykd.llm.service.LlmService;
import ykd.ykd.processor.ProcessResult;
import ykd.ykd.processor.UserContext;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 定时提醒管理器，使用 {@link ScheduledExecutorService} 实现本地定时触发。
 *
 * <p>与 {@link VideoTaskManager} 采用相同的 {@link Consumer} 回调 + 队列解耦模式，
 * 但触发机制不同：视频依赖外部 HTTP 轮询，提醒由 JDK 原生定时器自主触发。</p>
 */
@Slf4j
@Component
public class ReminderTaskManager {

    private final LlmService llmService;
    private final ChatClient deepseekClient;
    private final UserContext userContext;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, ReminderTask> tasks = new ConcurrentHashMap<>();
    private Consumer<ProcessResult> onCompleted;

    public ReminderTaskManager(@Lazy LlmService llmService,
                               @Qualifier("deepseekClient") ChatClient deepseekClient,
                               UserContext userContext) {
        this.llmService = llmService;
        this.deepseekClient = deepseekClient;
        this.userContext = userContext;
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    public void setOnCompleted(Consumer<ProcessResult> callback) {
        this.onCompleted = callback;
    }

    // ==================== 一次性提醒 ====================

    public String scheduleOnce(String userId, String message, String timeExpression) {
        long delaySeconds = parseDelay(timeExpression);
        if (delaySeconds < 0) {
            return "无法识别时间表达，请用'10分钟后'、'2小时后'或'30秒后'";
        }
        if (delaySeconds == 0) {
            return "⏰ 提醒时间已过，请检查时间";
        }

        String taskId = UUID.randomUUID().toString().substring(0, 8);
        ScheduledFuture<?> future;
        try {
            future = scheduler.schedule(() -> {
                try {
                    fire(userId, message);
                    tasks.remove(taskId);
                } catch (Exception e) {
                    log.error("[Reminder] 触发异常: taskId={}, userId={}", taskId, userId, e);
                }
            }, delaySeconds, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            log.error("[Reminder] 调度器拒绝任务: userId={}, msg={}", userId, message, e);
            return "⏰ 系统繁忙，请稍后再试";
        }

        tasks.put(taskId, new ReminderTask(taskId, userId, message, future, false, null));
        log.info("[Reminder] 一次性提醒已创建: taskId={}, userId={}, delay={}s, msg={}",
                taskId, userId, delaySeconds, message);
        return "⏰ 已设置提醒：" + message + "（" + formatDelay(delaySeconds) + "后通知）";
    }

    // ==================== 每日提醒 ====================

    public String scheduleDaily(String userId, String message, String timeExpression) {
        LocalTime target = parseDailyTime(timeExpression);
        if (target == null) {
            return "无法识别每日时间，请用'每天早上8点'或'每天08:00'";
        }

        long initialDelay = calculateInitialDelay(target);
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        ScheduledFuture<?> future;
        try {
            future = scheduler.scheduleAtFixedRate(() -> {
                try {
                    fire(userId, message);
                } catch (Exception e) {
                    log.error("[Reminder] 每日触发异常: taskId={}, userId={}", taskId, userId, e);
                }
            }, initialDelay, 86400, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            log.error("[Reminder] 调度器拒绝每日任务: userId={}, msg={}", userId, message, e);
            return "⏰ 系统繁忙，请稍后再试";
        }

        tasks.put(taskId, new ReminderTask(taskId, userId, message, future, true, target));
        log.info("[Reminder] 每日提醒已创建: taskId={}, userId={}, time={}, msg={}",
                taskId, userId, target, message);
        return "⏰ 已设置每日提醒：" + message + "，每天 " + target + " 通知";
    }

    // ==================== 查看 ====================

    public String listTasks(String userId) {
        List<ReminderTask> userTasks = tasks.values().stream()
                .filter(t -> t.userId.equals(userId))
                .toList();

        if (userTasks.isEmpty()) {
            return "当前没有待执行的提醒";
        }

        StringBuilder sb = new StringBuilder("📋 待执行提醒：\n");
        for (int i = 0; i < userTasks.size(); i++) {
            ReminderTask t = userTasks.get(i);
            if (t.daily) {
                sb.append(i + 1).append(". [每天] ").append(t.message)
                        .append("（").append(t.dailyTime).append("）\n");
            } else {
                long remaining = t.future.getDelay(TimeUnit.SECONDS);
                sb.append(i + 1).append(". [单次] ").append(t.message)
                        .append("（").append(formatDelay(Math.max(0, remaining))).append("后）\n");
            }
        }
        return sb.toString().trim();
    }

    // ==================== 取消 ====================

    public String cancelByIndex(String userId, int index) {
        List<ReminderTask> userTasks = tasks.values().stream()
                .filter(t -> t.userId.equals(userId))
                .toList();

        if (index < 1 || index > userTasks.size()) {
            return "序号无效，当前共 " + userTasks.size() + " 个提醒";
        }

        ReminderTask task = userTasks.get(index - 1);
        try {
            task.future.cancel(false);
        } catch (Exception e) {
            log.error("[Reminder] 取消失败: taskId={}", task.taskId, e);
            return "取消失败，请重试";
        }
        tasks.remove(task.taskId);
        log.info("[Reminder] 提醒已取消: taskId={}, userId={}, msg={}", task.taskId, userId, task.message);
        return "已取消提醒：" + task.message;
    }

    // ==================== 时间解析 ====================

    private long parseDelay(String expr) {
        Pattern p = Pattern.compile("(\\d+)\\s*(分钟|小时|秒)(后)?");
        Matcher m = p.matcher(expr);
        if (!m.find()) return -1;
        long v = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "秒" -> v;
            case "分钟" -> v * 60;
            case "小时" -> v * 3600;
            default -> -1;
        };
    }

    private LocalTime parseDailyTime(String expr) {
        Pattern p = Pattern.compile("(\\d{1,2})[:：点](\\d{0,2})");
        Matcher m = p.matcher(expr);
        if (!m.find()) return null;
        int hour = Integer.parseInt(m.group(1));
        String minStr = m.group(2);
        int minute = (minStr != null && !minStr.isEmpty()) ? Integer.parseInt(minStr) : 0;
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
        return LocalTime.of(hour, minute);
    }

    private long calculateInitialDelay(LocalTime target) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = now.toLocalDate().atTime(target);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Duration.between(now, next).getSeconds();
    }

    private String formatDelay(long seconds) {
        if (seconds < 60) return seconds + "秒";
        if (seconds < 3600) return (seconds / 60) + "分钟";
        long hours = seconds / 3600;
        long mins = (seconds % 3600) / 60;
        return mins > 0 ? hours + "小时" + mins + "分钟" : hours + "小时";
    }

    private void fire(String userId, String message) {
        if (onCompleted == null) {
            log.warn("[Reminder] 回调未设置，提醒丢失: userId={}, msg={}", userId, message);
            return;
        }
        userContext.executeAs(userId, () -> {
            try {
                String prompt = "⏰ 定时提醒：" + message;
                String reply = llmService.chat(prompt, null, deepseekClient, userId);
                log.info("[Reminder] LLM 回复: userId={}, reply={}",
                        userId, reply != null ? reply.substring(0, Math.min(100, reply.length())) : null);
                onCompleted.accept(ProcessResult.text(reply, userId));
            } catch (Exception e) {
                log.error("[Reminder] LLM 调用失败，降级发送原文: userId={}, msg={}", userId, message, e);
                onCompleted.accept(ProcessResult.text("⏰ 提醒：" + message, userId));
            }
        });
    }

    static class ReminderTask {
        final String taskId;
        final String userId;
        final String message;
        final ScheduledFuture<?> future;
        final boolean daily;
        final LocalTime dailyTime;

        ReminderTask(String taskId, String userId, String message,
                     ScheduledFuture<?> future, boolean daily, LocalTime dailyTime) {
            this.taskId = taskId;
            this.userId = userId;
            this.message = message;
            this.future = future;
            this.daily = daily;
            this.dailyTime = dailyTime;
        }
    }
}
