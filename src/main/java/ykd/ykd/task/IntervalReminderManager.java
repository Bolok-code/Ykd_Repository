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
import ykd.ykd.processor.UserContext;
import ykd.ykd.wxbot.WeixinBotService;

import java.time.LocalDateTime;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class IntervalReminderManager {

    private final LlmService llmService;
    private final ChatClient deepseekClient;
    private final UserContext userContext;
    private final WeixinBotService weixinBotService;
    private final ReminderTaskMapper reminderTaskMapper;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, IntervalTask> tasks = new ConcurrentHashMap<>();

    public IntervalReminderManager(@Lazy LlmService llmService,
                                   @Qualifier("deepseekClient") ChatClient deepseekClient,
                                   UserContext userContext,
                                   @Lazy WeixinBotService weixinBotService,
                                   ReminderTaskMapper reminderTaskMapper) {
        this.llmService = llmService;
        this.deepseekClient = deepseekClient;
        this.userContext = userContext;
        this.weixinBotService = weixinBotService;
        this.reminderTaskMapper = reminderTaskMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        List<ReminderTaskEntity> active = reminderTaskMapper.findAllActive();
        int recovered = 0;
        for (ReminderTaskEntity entity : active) {
            if (!"INTERVAL".equals(entity.getTaskType())) continue;
            try {
                long intervalSeconds = entity.getIntervalSeconds();
                long elapsedSeconds = (System.currentTimeMillis() - LocalDateTime.parse(entity.getCreatedAt().replace(" ", "T")).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()) / 1000;
                long remaining = intervalSeconds - (elapsedSeconds % intervalSeconds);
                if (remaining < 5) remaining = 5;

                IntervalTask task = new IntervalTask(entity.getTaskId(), entity.getUserId(),
                        entity.getMessage(), null, intervalSeconds, entity.getNeedsProcessing() == 1);

                ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
                    try {
                        fire(task);
                    } catch (Exception e) {
                        log.error("[IntervalReminder] 恢复任务触发异常: taskId={}, userId={}", task.taskId, task.userId, e);
                    }
                }, remaining, intervalSeconds, TimeUnit.SECONDS);
                task.future = future;

                tasks.put(task.taskId, task);
                recovered++;
                log.info("[IntervalReminder] 恢复间隔提醒: taskId={}, userId={}, interval={}s, remaining={}s, msg={}",
                        task.taskId, task.userId, intervalSeconds, remaining, task.message);
            } catch (Exception e) {
                log.error("[IntervalReminder] 恢复失败: taskId={}", entity.getTaskId(), e);
            }
        }
        if (recovered > 0) {
            log.info("[IntervalReminder] 共恢复 {} 个间隔提醒", recovered);
        }
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    public String scheduleInterval(String userId, String message, String timeExpression, boolean needsProcessing) {
        long intervalSeconds = parseInterval(timeExpression);
        if (intervalSeconds < 0) {
            return "无法识别间隔时间，请用'每隔2小时'、'每30分钟'或'每隔1小时'";
        }
        if (intervalSeconds < 1) {
            return "⏰ 间隔时间不能少于1秒";
        }

        String taskId = UUID.randomUUID().toString().substring(0, 8);
        IntervalTask task = new IntervalTask(taskId, userId, message, null, intervalSeconds, needsProcessing);
        ScheduledFuture<?> future;
        try {
            future = scheduler.scheduleAtFixedRate(() -> {
                try {
                    fire(task);
                } catch (Exception e) {
                    log.error("[IntervalReminder] 触发异常: taskId={}, userId={}", taskId, userId, e);
                }
            }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            log.error("[IntervalReminder] 调度器拒绝任务: userId={}, msg={}", userId, message, e);
            return "⏰ 系统繁忙，请稍后再试";
        }
        task.future = future;

        tasks.put(taskId, task);

        ReminderTaskEntity entity = new ReminderTaskEntity();
        entity.setTaskId(taskId);
        entity.setUserId(userId);
        entity.setMessage(message);
        entity.setTimeExpression(timeExpression);
        entity.setTaskType("INTERVAL");
        entity.setIntervalSeconds((int) intervalSeconds);
        entity.setNeedsProcessing(needsProcessing ? 1 : 0);
        reminderTaskMapper.insert(entity);

        log.info("[IntervalReminder] 间隔提醒已创建: taskId={}, userId={}, interval={}s, msg={}, needsProcessing={}",
                taskId, userId, intervalSeconds, message, needsProcessing);
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
        reminderTaskMapper.cancelByTaskId(task.taskId);
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

    private void fire(IntervalTask task) {
        if (task.needsProcessing) {
            fireWithLLM(task);
        } else {
            weixinBotService.sendTextToUser(task.userId, task.message);
        }
    }

    private void fireWithLLM(IntervalTask task) {
        userContext.executeAs(task.userId, () -> {
            try {
                String prompt = "⏰ 定时提醒：" + task.message;
                String reply = llmService.chat(prompt, null, deepseekClient, task.userId);
                log.info("[IntervalReminder] LLM 回复: userId={}, reply={}",
                        task.userId, reply != null ? reply.substring(0, Math.min(100, reply.length())) : null);
                weixinBotService.sendTextToUser(task.userId, reply);
            } catch (Exception e) {
                log.error("[IntervalReminder] LLM 调用失败，降级发送原文: userId={}, msg={}",
                        task.userId, task.message, e);
                weixinBotService.sendTextToUser(task.userId, "⏰ 提醒：" + task.message);
            }
        });
    }

    static class IntervalTask {
        final String taskId;
        final String userId;
        final String message;
        volatile ScheduledFuture<?> future;
        final long intervalSeconds;
        final boolean needsProcessing;

        IntervalTask(String taskId, String userId, String message,
                     ScheduledFuture<?> future, long intervalSeconds, boolean needsProcessing) {
            this.taskId = taskId;
            this.userId = userId;
            this.message = message;
            this.future = future;
            this.intervalSeconds = intervalSeconds;
            this.needsProcessing = needsProcessing;
        }
    }
}
