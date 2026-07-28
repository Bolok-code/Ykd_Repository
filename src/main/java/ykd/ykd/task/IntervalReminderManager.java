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

@Slf4j
@Component
public class IntervalReminderManager {

    private final LlmService llmService;
    private final ChatClient deepseekClient;
    private final UserContext userContext;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, IntervalTask> tasks = new ConcurrentHashMap<>();
    private Consumer<ProcessResult> onCompleted;

    public IntervalReminderManager(@Lazy LlmService llmService,
                                   @Qualifier("deepseekClient") ChatClient deepseekClient,
                                   UserContext userContext) {
        this.llmService = llmService;
        this.deepseekClient = deepseekClient;
        this.userContext = userContext;
    }

    public void setOnCompleted(Consumer<ProcessResult> callback) {
        this.onCompleted = callback;
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    public String scheduleInterval(String userId, String message, String timeExpression) {
        long intervalSeconds = parseInterval(timeExpression);
        if (intervalSeconds < 0) {
            return "无法识别间隔时间，请用'每隔2小时'、'每30分钟'或'每隔1小时'";
        }
        if (intervalSeconds < 60) {
            return "⏰ 间隔时间不能少于1分钟";
        }

        String taskId = UUID.randomUUID().toString().substring(0, 8);
        ScheduledFuture<?> future;
        try {
            future = scheduler.scheduleAtFixedRate(() -> {
                try {
                    fire(userId, message);
                } catch (Exception e) {
                    log.error("[IntervalReminder] 触发异常: taskId={}, userId={}", taskId, userId, e);
                }
            }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            log.error("[IntervalReminder] 调度器拒绝任务: userId={}, msg={}", userId, message, e);
            return "⏰ 系统繁忙，请稍后再试";
        }

        tasks.put(taskId, new IntervalTask(taskId, userId, message, future, intervalSeconds));
        log.info("[IntervalReminder] 间隔提醒已创建: taskId={}, userId={}, interval={}s, msg={}",
                taskId, userId, intervalSeconds, message);
        return "⏰ 已设置间隔提醒：" + message + "，每隔 " + formatDuration(intervalSeconds) + " 通知";
    }

    public String listTasks(String userId) {
        List<IntervalTask> userTasks = tasks.values().stream()
                .filter(t -> t.userId.equals(userId))
                .toList();

        if (userTasks.isEmpty()) {
            return "当前没有待执行的间隔提醒";
        }

        StringBuilder sb = new StringBuilder("📋 间隔提醒：\n");
        for (int i = 0; i < userTasks.size(); i++) {
            IntervalTask t = userTasks.get(i);
            sb.append(i + 1).append(". ").append(t.message)
                    .append("（每").append(formatDuration(t.intervalSeconds)).append("）\n");
        }
        return sb.toString().trim();
    }

    public String cancelByIndex(String userId, int index) {
        List<IntervalTask> userTasks = tasks.values().stream()
                .filter(t -> t.userId.equals(userId))
                .toList();

        if (index < 1 || index > userTasks.size()) {
            return "序号无效，当前共 " + userTasks.size() + " 个间隔提醒";
        }

        IntervalTask task = userTasks.get(index - 1);
        try {
            task.future.cancel(false);
        } catch (Exception e) {
            log.error("[IntervalReminder] 取消失败: taskId={}", task.taskId, e);
            return "取消失败，请重试";
        }
        tasks.remove(task.taskId);
        log.info("[IntervalReminder] 间隔提醒已取消: taskId={}, userId={}, msg={}", task.taskId, userId, task.message);
        return "已取消间隔提醒：" + task.message;
    }

    private long parseInterval(String expr) {
        Pattern p = Pattern.compile("每(?:隔)?\\s*(\\d+)\\s*(小时|分钟|秒)");
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

    private String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "秒";
        if (seconds < 3600) return (seconds / 60) + "分钟";
        long hours = seconds / 3600;
        long mins = (seconds % 3600) / 60;
        return mins > 0 ? hours + "小时" + mins + "分钟" : hours + "小时";
    }

    private void fire(String userId, String message) {
        if (onCompleted == null) {
            log.warn("[IntervalReminder] 回调未设置，提醒丢失: userId={}, msg={}", userId, message);
            return;
        }
        userContext.executeAs(userId, () -> {
            try {
                String prompt = "⏰ 定时提醒：" + message;
                String reply = llmService.chat(prompt, null, deepseekClient, userId);
                log.info("[IntervalReminder] LLM 回复: userId={}, reply={}",
                        userId, reply != null ? reply.substring(0, Math.min(100, reply.length())) : null);
                onCompleted.accept(ProcessResult.text(reply, userId));
            } catch (Exception e) {
                log.error("[IntervalReminder] LLM 调用失败，降级发送原文: userId={}, msg={}", userId, message, e);
                onCompleted.accept(ProcessResult.text("⏰ 提醒：" + message, userId));
            }
        });
    }

    static class IntervalTask {
        final String taskId;
        final String userId;
        final String message;
        final ScheduledFuture<?> future;
        final long intervalSeconds;

        IntervalTask(String taskId, String userId, String message,
                     ScheduledFuture<?> future, long intervalSeconds) {
            this.taskId = taskId;
            this.userId = userId;
            this.message = message;
            this.future = future;
            this.intervalSeconds = intervalSeconds;
        }
    }
}
