package ykd.ykd.task;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ykd.ykd.llm.service.LlmService;
import ykd.ykd.memory.mapper.ReminderTaskMapper;
import ykd.ykd.memory.model.ReminderTaskEntity;
import ykd.ykd.processor.ProcessResult;
import ykd.ykd.processor.UserContext;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
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
public class ReminderTaskManager {

    private final LlmService llmService;
    private final ChatClient deepseekClient;
    private final UserContext userContext;
    private final ReminderTaskMapper reminderTaskMapper;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, ReminderTask> tasks = new ConcurrentHashMap<>();
    private Consumer<ProcessResult> onCompleted;

    public ReminderTaskManager(@Lazy LlmService llmService,
                               @Qualifier("deepseekClient") ChatClient deepseekClient,
                               UserContext userContext,
                               ReminderTaskMapper reminderTaskMapper) {
        this.llmService = llmService;
        this.deepseekClient = deepseekClient;
        this.userContext = userContext;
        this.reminderTaskMapper = reminderTaskMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        List<ReminderTaskEntity> active = reminderTaskMapper.findAllActive();
        int recovered = 0;
        for (ReminderTaskEntity entity : active) {
            String type = entity.getTaskType();
            if ("INTERVAL".equals(type)) continue;
            try {
                boolean needsProcessing = entity.getNeedsProcessing() == 1;
                ReminderTask task;

                if ("ONCE".equals(type)) {
                    long delaySeconds = entity.getDelaySeconds();
                    long elapsedSeconds = (System.currentTimeMillis() - LocalDateTime.parse(entity.getCreatedAt().replace(" ", "T")).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()) / 1000;
                    long remaining = delaySeconds - elapsedSeconds;
                    if (remaining < 5) remaining = 5;

                    task = new ReminderTask(entity.getTaskId(), entity.getUserId(), entity.getMessage(),
                            null, false, null, needsProcessing);
                    ScheduledFuture<?> future = scheduler.schedule(() -> {
                        try {
                            fire(task);
                            tasks.remove(task.taskId);
                            reminderTaskMapper.cancelByTaskId(task.taskId);
                        } catch (Exception e) {
                            log.error("[Reminder] 恢复任务触发异常: taskId={}, userId={}", task.taskId, task.userId, e);
                        }
                    }, remaining, TimeUnit.SECONDS);
                    task.future = future;
                    tasks.put(task.taskId, task);

                    log.info("[Reminder] 恢复一次性提醒: taskId={}, userId={}, remaining={}s, msg={}",
                            task.taskId, task.userId, remaining, task.message);
                } else if ("DAILY".equals(type)) {
                    LocalTime dailyTime = LocalTime.parse(entity.getDailyTime());
                    long initialDelay = calculateInitialDelay(dailyTime);
                    if (initialDelay < 5) initialDelay = 5;

                    task = new ReminderTask(entity.getTaskId(), entity.getUserId(), entity.getMessage(),
                            null, true, dailyTime, needsProcessing);
                    ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
                        try {
                            fire(task);
                        } catch (Exception e) {
                            log.error("[Reminder] 每日恢复触发异常: taskId={}, userId={}", task.taskId, task.userId, e);
                        }
                    }, initialDelay, 86400, TimeUnit.SECONDS);
                    task.future = future;
                    tasks.put(task.taskId, task);

                    log.info("[Reminder] 恢复每日提醒: taskId={}, userId={}, time={}, msg={}",
                            task.taskId, task.userId, dailyTime, task.message);
                }
                recovered++;
            } catch (Exception e) {
                log.error("[Reminder] 恢复失败: taskId={}", entity.getTaskId(), e);
            }
        }
        if (recovered > 0) {
            log.info("[Reminder] 共恢复 {} 个提醒", recovered);
        }
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    public void setOnCompleted(Consumer<ProcessResult> callback) {
        this.onCompleted = callback;
    }

    public String scheduleOnce(String userId, String message, String timeExpression, boolean needsProcessing) {
        long delaySeconds = parseDelay(timeExpression);
        if (delaySeconds < 0) {
            return "无法识别时间表达，请用'10分钟后'、'2小时后'或'30秒后'";
        }
        if (delaySeconds == 0) {
            return "⏰ 提醒时间已过，请检查时间";
        }

        String taskId = UUID.randomUUID().toString().substring(0, 8);
        ReminderTask task = new ReminderTask(taskId, userId, message, null, false, null, needsProcessing);
        ScheduledFuture<?> future;
        try {
            future = scheduler.schedule(() -> {
                try {
                    fire(task);
                    tasks.remove(taskId);
                    reminderTaskMapper.cancelByTaskId(taskId);
                } catch (Exception e) {
                    log.error("[Reminder] 触发异常: taskId={}, userId={}", taskId, userId, e);
                }
            }, delaySeconds, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            log.error("[Reminder] 调度器拒绝任务: userId={}, msg={}", userId, message, e);
            return "⏰ 系统繁忙，请稍后再试";
        }
        task.future = future;

        tasks.put(taskId, task);

        ReminderTaskEntity entity = new ReminderTaskEntity();
        entity.setTaskId(taskId);
        entity.setUserId(userId);
        entity.setMessage(message);
        entity.setTimeExpression(timeExpression);
        entity.setTaskType("ONCE");
        entity.setDelaySeconds((int) delaySeconds);
        entity.setNeedsProcessing(needsProcessing ? 1 : 0);
        reminderTaskMapper.insert(entity);

        log.info("[Reminder] 一次性提醒已创建: taskId={}, userId={}, delay={}s, msg={}, needsProcessing={}",
                taskId, userId, delaySeconds, message, needsProcessing);
        return "⏰ 已设置提醒：" + message + "（" + formatDelay(delaySeconds) + "后通知）";
    }

    public String scheduleDaily(String userId, String message, String timeExpression, boolean needsProcessing) {
        LocalTime target = parseDailyTime(timeExpression);
        if (target == null) {
            return "无法识别每日时间，请用'每天早上8点'或'每天08:00'";
        }

        long initialDelay = calculateInitialDelay(target);
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        ReminderTask task = new ReminderTask(taskId, userId, message, null, true, target, needsProcessing);
        ScheduledFuture<?> future;
        try {
            future = scheduler.scheduleAtFixedRate(() -> {
                try {
                    fire(task);
                } catch (Exception e) {
                    log.error("[Reminder] 每日触发异常: taskId={}, userId={}", taskId, userId, e);
                }
            }, initialDelay, 86400, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            log.error("[Reminder] 调度器拒绝每日任务: userId={}, msg={}", userId, message, e);
            return "⏰ 系统繁忙，请稍后再试";
        }
        task.future = future;

        tasks.put(taskId, task);

        ReminderTaskEntity entity = new ReminderTaskEntity();
        entity.setTaskId(taskId);
        entity.setUserId(userId);
        entity.setMessage(message);
        entity.setTimeExpression(timeExpression);
        entity.setTaskType("DAILY");
        entity.setDailyTime(target.toString());
        entity.setNeedsProcessing(needsProcessing ? 1 : 0);
        reminderTaskMapper.insert(entity);

        log.info("[Reminder] 每日提醒已创建: taskId={}, userId={}, time={}, msg={}, needsProcessing={}",
                taskId, userId, target, message, needsProcessing);
        return "⏰ 已设置每日提醒：" + message + "，每天 " + target + " 通知";
    }

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
        reminderTaskMapper.cancelByTaskId(task.taskId);
        log.info("[Reminder] 提醒已取消: taskId={}, userId={}, msg={}", task.taskId, userId, task.message);
        return "已取消提醒：" + task.message;
    }

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

    private void fire(ReminderTask task) {
        if (task.needsProcessing) {
            fireWithLLM(task);
        } else {
            fireDirect(task);
        }
    }

    private void fireDirect(ReminderTask task) {
        if (onCompleted == null) {
            log.warn("[Reminder] 回调未设置，提醒丢失: userId={}, msg={}", task.userId, task.message);
            return;
        }
        onCompleted.accept(ProcessResult.text("⏰ 提醒：" + task.message, task.userId));
    }

    private void fireWithLLM(ReminderTask task) {
        if (onCompleted == null) {
            log.warn("[Reminder] 回调未设置，提醒丢失: userId={}, msg={}", task.userId, task.message);
            return;
        }
        userContext.executeAs(task.userId, () -> {
            try {
                String prompt = "⏰ 定时提醒：" + task.message;
                String reply = llmService.chat(prompt, null, deepseekClient, task.userId);
                log.info("[Reminder] LLM 回复: userId={}, reply={}",
                        task.userId, reply != null ? reply.substring(0, Math.min(100, reply.length())) : null);
                onCompleted.accept(ProcessResult.text(reply, task.userId));
            } catch (Exception e) {
                log.error("[Reminder] LLM 调用失败，降级发送原文: userId={}, msg={}",
                        task.userId, task.message, e);
                onCompleted.accept(ProcessResult.text("⏰ 提醒：" + task.message, task.userId));
            }
        });
    }

    static class ReminderTask {
        final String taskId;
        final String userId;
        final String message;
        volatile ScheduledFuture<?> future;
        final boolean daily;
        final LocalTime dailyTime;
        final boolean needsProcessing;

        ReminderTask(String taskId, String userId, String message,
                     ScheduledFuture<?> future, boolean daily, LocalTime dailyTime, boolean needsProcessing) {
            this.taskId = taskId;
            this.userId = userId;
            this.message = message;
            this.future = future;
            this.daily = daily;
            this.dailyTime = dailyTime;
            this.needsProcessing = needsProcessing;
        }
    }
}
