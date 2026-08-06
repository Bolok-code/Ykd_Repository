package ykd.ykd.task;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import ykd.ykd.llm.service.LlmService;
import ykd.ykd.memory.mapper.ReminderTaskMapper;
import ykd.ykd.memory.model.ReminderTaskEntity;
import ykd.ykd.processor.UserContext;
import ykd.ykd.wxbot.LoginReadyEvent;
import ykd.ykd.wxbot.WeixinBotService;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class UnifiedReminderManager {

    private static final Map<String, Integer> WEEKDAY_MAP = Map.of(
            "一", 1, "二", 2, "三", 3, "四", 4, "五", 5, "六", 6, "日", 7, "天", 7
    );

    private final LlmService llmService;
    private final ChatClient deepseekClient;
    private final UserContext userContext;
    private final ReminderTaskMapper reminderTaskMapper;
    private final WeixinBotService weixinBotService;
    private final ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
    private final Map<String, ReminderTask> tasks = new ConcurrentHashMap<>();
    private final Set<String> pausedTaskIds = ConcurrentHashMap.newKeySet();
    /** 任务被暂停的时刻（taskId → 时间戳），用于清理长期未恢复的暂停任务 */
    private final Map<String, Long> pausedAt = new ConcurrentHashMap<>();
    /** 暂停任务保留时长：超时且用户一直没发消息恢复的，清理掉并取消 DB 记录 */
    private static final long STALE_PAUSED_MS = 7L * 24 * 60 * 60 * 1000;

    /** 提醒排序：先按创建时间，时间相同按插入序号，保证同毫秒内调度也能保持插入顺序 */
    private static final Comparator<ReminderTask> TaskOrder =
            Comparator.comparing((ReminderTask t) -> t.createdAt)
                    .thenComparingLong(t -> t.sequence);

    public UnifiedReminderManager(@Lazy LlmService llmService,
                                   @Qualifier("deepseekClient") ChatClient deepseekClient,
                                   UserContext userContext,
                                   ReminderTaskMapper reminderTaskMapper,
                                   @Lazy WeixinBotService weixinBotService) {
        this.llmService = llmService;
        this.deepseekClient = deepseekClient;
        this.userContext = userContext;
        this.reminderTaskMapper = reminderTaskMapper;
        this.weixinBotService = weixinBotService;
    }

    @PostConstruct
    public void init() {
        taskScheduler.setPoolSize(4);
        taskScheduler.setThreadNamePrefix("reminder-");
        taskScheduler.initialize();
        // 定期清理长期未恢复的暂停任务，1 小时后开始，每 6 小时一次
        taskScheduler.scheduleAtFixedRate(this::sweepStalePausedTasks,
                Instant.now().plusSeconds(3600), Duration.ofHours(6));
    }

    /**
     * 清理超过 STALE_PAUSED_MS 仍未恢复的暂停任务（用户可能已放弃该提醒）。
     * 同时取消 DB 记录，避免下次启动恢复时重新加载。
     */
    private void sweepStalePausedTasks() {
        if (pausedAt.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : pausedAt.entrySet()) {
            String taskId = entry.getKey();
            if (now - entry.getValue() < STALE_PAUSED_MS) continue;
            ReminderTask task = tasks.remove(taskId);
            pausedAt.remove(taskId);
            pausedTaskIds.remove(taskId);
            if (task != null && task.future != null) {
                try { task.future.cancel(false); } catch (Exception ignored) {}
            }
            try {
                reminderTaskMapper.cancelByTaskId(taskId);
            } catch (Exception e) {
                log.warn("[Reminder] 清理暂停任务时取消 DB 记录失败: taskId={}", taskId);
            }
            log.info("[Reminder] 清理长期未恢复的暂停任务: taskId={}", taskId);
        }
    }

    @PreDestroy
    public void stop() {
        taskScheduler.shutdown();
    }

    // ── Recovery ──────────────────────────────────────────────

    private final AtomicBoolean recovered = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        if (!weixinBotService.awaitReady(60)) {
            log.warn("[Reminder] 等待登录超时，将在登录完成后自动恢复");
            return;
        }
        recover();
    }

    @EventListener(LoginReadyEvent.class)
    public void onLoginReady(LoginReadyEvent event) {
        recover();
    }

    public void recover() {
        if (!recovered.compareAndSet(false, true)) {
            log.info("[Reminder] 已恢复过，跳过重复恢复");
            return;
        }
        List<ReminderTaskEntity> active = reminderTaskMapper.findAllActive();
        int recoveredCount = 0;
        for (ReminderTaskEntity entity : active) {
            try {
                boolean needsProcessing = entity.getNeedsProcessing() == 1;
                String type = entity.getTaskType();
                ReminderTask task;
                ScheduledFuture<?> future;

                switch (type) {
                    case "ONCE" -> {
                        long elapsed = (System.currentTimeMillis() - parseCreatedAt(entity.getCreatedAt())) / 1000;
                        long remaining = entity.getDelaySeconds() - elapsed;
                        if (remaining < 10) remaining = 10;

                        task = new ReminderTask(entity.getTaskId(), entity.getUserId(), entity.getMessage(),
                                type, null, 0, needsProcessing);
                        future = taskScheduler.schedule(() -> fireOnce(task), Instant.now().plusSeconds(remaining));
                        log.info("[Reminder] 恢复一次性: taskId={}, userId={}, remaining={}s", task.taskId, task.userId, remaining);
                    }
                    case "DAILY", "WEEKLY" -> {
                        String cronExpr = entity.getCronExpression();
                        if (cronExpr == null || cronExpr.isBlank()) {
                            log.warn("[Reminder] 恢复跳过(无cron): taskId={}, type={}", entity.getTaskId(), type);
                            continue;
                        }
                        task = new ReminderTask(entity.getTaskId(), entity.getUserId(), entity.getMessage(),
                                type, cronExpr, 0, needsProcessing);
                        future = taskScheduler.schedule(() -> fire(task), new CronTrigger(cronExpr));
                        log.info("[Reminder] 恢复{}: taskId={}, userId={}, cron={}", type, task.taskId, task.userId, cronExpr);
                    }
                    case "INTERVAL" -> {
                        long interval = entity.getIntervalSeconds();
                        long elapsed = (System.currentTimeMillis() - parseCreatedAt(entity.getCreatedAt())) / 1000;
                        long remaining = interval - (elapsed % interval);
                        if (remaining < 10) remaining = 10;

                        task = new ReminderTask(entity.getTaskId(), entity.getUserId(), entity.getMessage(),
                                type, null, interval, needsProcessing);
                        future = taskScheduler.scheduleAtFixedRate(() -> fire(task),
                                Instant.now().plusSeconds(remaining), Duration.ofSeconds(interval));
                        log.info("[Reminder] 恢复间隔: taskId={}, userId={}, interval={}s, remaining={}s", task.taskId, task.userId, interval, remaining);
                    }
                    default -> {
                        log.warn("[Reminder] 恢复跳过(未知类型): taskId={}, type={}", entity.getTaskId(), type);
                        continue;
                    }
                }
                task.future = future;
                tasks.put(task.taskId, task);
                recoveredCount++;
            } catch (Exception e) {
                log.error("[Reminder] 恢复失败: taskId={}", entity.getTaskId(), e);
            }
        }
        if (recoveredCount > 0) {
            log.info("[Reminder] 共恢复 {} 个提醒", recoveredCount);
        }
    }

    // ── Schedule ──────────────────────────────────────────────

    public String scheduleOnce(String userId, String message, String timeExpression, boolean needsProcessing) {
        long delaySeconds = parseDelay(timeExpression);
        if (delaySeconds < 0) return "无法识别时间表达，请用'10分钟后'、'2小时后'或'30秒后'";
        if (delaySeconds == 0) return "⏰ 提醒时间已过，请检查时间";

        String taskId = newTaskId();
        ReminderTask task = new ReminderTask(taskId, userId, message, "ONCE", null, 0, needsProcessing);
        ScheduledFuture<?> future;
        try {
            future = taskScheduler.schedule(() -> fireOnce(task), Instant.now().plusSeconds(delaySeconds));
        } catch (RejectedExecutionException e) {
            log.error("[Reminder] 调度器拒绝: userId={}, msg={}", userId, message, e);
            return "⏰ 系统繁忙，请稍后再试";
        }
        task.future = future;
        tasks.put(taskId, task);

        ReminderTaskEntity entity = buildEntity(taskId, userId, message, timeExpression, "ONCE", needsProcessing);
        entity.setDelaySeconds((int) delaySeconds);
        reminderTaskMapper.insert(entity);

        log.info("[Reminder] 一次性: taskId={}, userId={}, delay={}s, msg={}", taskId, userId, delaySeconds, message);
        return "⏰ 已设置提醒：" + message + "（" + formatDelay(delaySeconds) + "后通知）";
    }

    public String scheduleDaily(String userId, String message, String timeExpression, boolean needsProcessing) {
        LocalTime target = parseDailyTime(timeExpression);
        if (target == null) return "无法识别每日时间，请用'每天早上8点'或'每天08:00'";

        String cronExpr = String.format("0 %d %d * * ?", target.getMinute(), target.getHour());
        return scheduleCron(userId, message, timeExpression, "DAILY", cronExpr, needsProcessing,
                "每日", target.toString());
    }

    public String scheduleWeekly(String userId, String message, String timeExpression, boolean needsProcessing) {
        WeeklyTime wt = parseWeeklyTime(timeExpression);
        if (wt == null) return "无法识别每周时间，请用'每周日早上8点'或'每周一08:30'";

        String dayName = WEEKDAY_MAP.entrySet().stream()
                .filter(e -> e.getValue() == wt.dayOfWeek).map(Map.Entry::getKey).findFirst().orElse("?");
        String cronExpr = String.format("0 %d %d ? * %d", wt.time.getMinute(), wt.time.getHour(), wt.dayOfWeek);
        return scheduleCron(userId, message, timeExpression, "WEEKLY", cronExpr, needsProcessing,
                "每周" + dayName, wt.time.toString());
    }

    private String scheduleCron(String userId, String message, String timeExpression,
                                 String taskType, String cronExpr, boolean needsProcessing,
                                 String label, String timeLabel) {
        String taskId = newTaskId();
        ReminderTask task = new ReminderTask(taskId, userId, message, taskType, cronExpr, 0, needsProcessing);
        ScheduledFuture<?> future;
        try {
            future = taskScheduler.schedule(() -> fire(task), new CronTrigger(cronExpr));
        } catch (RejectedExecutionException e) {
            log.error("[Reminder] 调度器拒绝: userId={}, msg={}", userId, message, e);
            return "⏰ 系统繁忙，请稍后再试";
        }
        task.future = future;
        tasks.put(taskId, task);

        ReminderTaskEntity entity = buildEntity(taskId, userId, message, timeExpression, taskType, needsProcessing);
        entity.setCronExpression(cronExpr);
        reminderTaskMapper.insert(entity);

        log.info("[Reminder] {}: taskId={}, userId={}, cron={}, msg={}", taskType, taskId, userId, cronExpr, message);
        return "⏰ 已设置" + label + "提醒：" + message + "（" + timeLabel + "通知）";
    }

    public String scheduleInterval(String userId, String message, String timeExpression, boolean needsProcessing) {
        long intervalSeconds = parseInterval(timeExpression);
        if (intervalSeconds < 0) return "无法识别间隔时间，请用'每隔2小时'、'每30分钟'或'每隔1小时'";
        if (intervalSeconds < 1) return "⏰ 间隔时间不能少于1秒";

        String taskId = newTaskId();
        ReminderTask task = new ReminderTask(taskId, userId, message, "INTERVAL", null, intervalSeconds, needsProcessing);
        ScheduledFuture<?> future;
        try {
            future = taskScheduler.scheduleAtFixedRate(() -> fire(task),
                    Instant.now().plusSeconds(intervalSeconds), Duration.ofSeconds(intervalSeconds));
        } catch (RejectedExecutionException e) {
            log.error("[Reminder] 调度器拒绝: userId={}, msg={}", userId, message, e);
            return "⏰ 系统繁忙，请稍后再试";
        }
        task.future = future;
        tasks.put(taskId, task);

        ReminderTaskEntity entity = buildEntity(taskId, userId, message, timeExpression, "INTERVAL", needsProcessing);
        entity.setIntervalSeconds((int) intervalSeconds);
        reminderTaskMapper.insert(entity);

        log.info("[Reminder] 间隔: taskId={}, userId={}, interval={}s, msg={}", taskId, userId, intervalSeconds, message);
        return "⏰ 已设置间隔提醒：" + message + "，每隔 " + formatDuration(intervalSeconds) + " 通知";
    }

    // ── List & Cancel ─────────────────────────────────────────

    public String listTasks(String userId) {
        List<ReminderTask> userTasks = tasks.values().stream()
                .filter(t -> t.userId.equals(userId))
                .sorted(TaskOrder)
                .toList();

        if (userTasks.isEmpty()) return "当前没有待执行的提醒";

        StringBuilder sb = new StringBuilder("📋 待执行提醒：\n");
        for (int i = 0; i < userTasks.size(); i++) {
            ReminderTask t = userTasks.get(i);
            switch (t.taskType) {
                case "DAILY" -> sb.append(i + 1).append(". [每天] ").append(t.message)
                        .append("（cron: ").append(t.cronExpression).append("）\n");
                case "WEEKLY" -> sb.append(i + 1).append(". [每周] ").append(t.message)
                        .append("（cron: ").append(t.cronExpression).append("）\n");
                case "INTERVAL" -> sb.append(i + 1).append(". [间隔] ").append(t.message)
                        .append("（每").append(formatDuration(t.intervalSeconds)).append("）\n");
                default -> {
                    long remaining = t.future != null ? t.future.getDelay(TimeUnit.SECONDS) : 0;
                    sb.append(i + 1).append(". [单次] ").append(t.message)
                            .append("（").append(formatDelay(Math.max(0, remaining))).append("后）\n");
                }
            }
        }
        return sb.toString().trim();
    }

    public String cancelByIndex(String userId, int index) {
        List<ReminderTask> userTasks = tasks.values().stream()
                .filter(t -> t.userId.equals(userId))
                .sorted(TaskOrder)
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
        pausedTaskIds.remove(task.taskId);
        pausedAt.remove(task.taskId);
        reminderTaskMapper.cancelByTaskId(task.taskId);
        log.info("[Reminder] 已取消: taskId={}, userId={}, msg={}", task.taskId, userId, task.message);
        return "已取消提醒：" + task.message;
    }

    // ── Fire ──────────────────────────────────────────────────

    private void fire(ReminderTask task) {
        if (task.needsProcessing) {
            fireWithLLM(task);
        } else {
            fireDirect(task);
        }
    }

    private void fireOnce(ReminderTask task) {
        fire(task);
        tasks.remove(task.taskId);
        reminderTaskMapper.cancelByTaskId(task.taskId);
    }

    private void fireDirect(ReminderTask task) {
        if (pausedTaskIds.contains(task.taskId)) {
            return;
        }
        boolean ok = weixinBotService.sendTextWithResult(task.userId, "⏰ 提醒：" + task.message);
        if (!ok) {
            pauseTask(task);
        }
    }

    private void fireWithLLM(ReminderTask task) {
        if (pausedTaskIds.contains(task.taskId)) {
            return;
        }
        userContext.executeAs(task.userId, () -> {
            try {
                String prompt = "⏰ 定时提醒：" + task.message;
                // 提醒是系统生成的消息：关闭技能路由，避免被用户活跃的技能会话劫持
                //（如猎聘会话锁住工具导致天气提醒无法查询），同时不改变用户技能会话状态
                String reply = llmService.chat(prompt, null, deepseekClient, task.userId, null, false);
                log.info("[Reminder] LLM 回复: userId={}, reply={}",
                        task.userId, reply != null ? reply.substring(0, Math.min(100, reply.length())) : null);
                weixinBotService.sendTextToUser(task.userId, reply);
            } catch (Exception e) {
                log.error("[Reminder] LLM 调用失败，降级: userId={}, msg={}", task.userId, task.message, e);
                weixinBotService.sendTextToUser(task.userId, "⏰ 提醒：" + task.message);
            }
        });
    }

    // ── Pause / Resume ──────────────────────────────────────

    private void pauseTask(ReminderTask task) {
        pausedTaskIds.add(task.taskId);
        pausedAt.put(task.taskId, System.currentTimeMillis());
        if (task.future != null) {
            task.future.cancel(false);
        }
        log.warn("[Reminder] 协议过期，暂停任务: taskId={}, userId={}, msg={}", task.taskId, task.userId, task.message);
        try {
            weixinBotService.sendTextToUser(task.userId, "⏰ 定时任务已暂停（协议过期），发任意消息自动恢复");
        } catch (Exception ignored) {}
    }

    /**
     * 恢复指定用户的所有暂停任务。用户发消息时调用。
     */
    public void resumeAllForUser(String userId) {
        List<ReminderTask> toResume = tasks.values().stream()
                .filter(t -> t.userId.equals(userId) && pausedTaskIds.contains(t.taskId))
                .toList();
        if (toResume.isEmpty()) return;
        log.info("[Reminder] 恢复暂停任务: userId={}, count={}", userId, toResume.size());
        for (ReminderTask task : toResume) {
            pausedTaskIds.remove(task.taskId);
            pausedAt.remove(task.taskId);
            rescheduleTask(task);
        }
        try {
            weixinBotService.sendTextToUser(userId, "✅ 定时任务已恢复，共" + toResume.size() + "个");
        } catch (Exception ignored) {}
    }

    private void rescheduleTask(ReminderTask task) {
        try {
            ScheduledFuture<?> future = switch (task.taskType) {
                case "INTERVAL" -> taskScheduler.scheduleAtFixedRate(
                        () -> fire(task),
                        Instant.now().plusSeconds(task.intervalSeconds),
                        Duration.ofSeconds(task.intervalSeconds));
                case "CRON" -> taskScheduler.schedule(
                        () -> fire(task), new CronTrigger(task.cronExpression));
                default -> null;
            };
            task.future = future;
        } catch (Exception e) {
            log.error("[Reminder] 恢复任务失败: taskId={}", task.taskId, e);
        }
    }

    // ── Parsing ───────────────────────────────────────────────

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

    private WeeklyTime parseWeeklyTime(String expr) {
        Pattern dayP = Pattern.compile("每周([一二三四五六日天])");
        Matcher dayM = dayP.matcher(expr);
        if (!dayM.find()) return null;
        Integer dayOfWeek = WEEKDAY_MAP.get(dayM.group(1));
        if (dayOfWeek == null) return null;

        Pattern timeP = Pattern.compile("(\\d{1,2})[:：点](\\d{0,2})");
        Matcher timeM = timeP.matcher(expr);
        if (!timeM.find()) return null;
        int hour = Integer.parseInt(timeM.group(1));
        String minStr = timeM.group(2);
        int minute = (minStr != null && !minStr.isEmpty()) ? Integer.parseInt(minStr) : 0;
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;

        return new WeeklyTime(dayOfWeek, LocalTime.of(hour, minute));
    }

    private record WeeklyTime(int dayOfWeek, LocalTime time) {}

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

    // ── Helpers ───────────────────────────────────────────────

    private String newTaskId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private long parseCreatedAt(String createdAt) {
        return LocalDateTime.parse(createdAt.replace(" ", "T"))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private ReminderTaskEntity buildEntity(String taskId, String userId, String message,
                                            String timeExpression, String taskType,
                                            boolean needsProcessing) {
        ReminderTaskEntity entity = new ReminderTaskEntity();
        entity.setTaskId(taskId);
        entity.setUserId(userId);
        entity.setMessage(message);
        entity.setTimeExpression(timeExpression);
        entity.setTaskType(taskType);
        entity.setNeedsProcessing(needsProcessing ? 1 : 0);
        return entity;
    }

    private String formatDelay(long seconds) {
        if (seconds < 60) return seconds + "秒";
        if (seconds < 3600) return (seconds / 60) + "分钟";
        long hours = seconds / 3600;
        long mins = (seconds % 3600) / 60;
        return mins > 0 ? hours + "小时" + mins + "分钟" : hours + "小时";
    }

    private String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "秒";
        if (seconds < 3600) return (seconds / 60) + "分钟";
        long hours = seconds / 3600;
        long mins = (seconds % 3600) / 60;
        return mins > 0 ? hours + "小时" + mins + "分钟" : hours + "小时";
    }

    // ── Task ──────────────────────────────────────────────────

    static class ReminderTask {
        /** 单调递增序号，用于同一时间戳（createdAt 同毫秒/微秒）下保持插入顺序 */
        private static final AtomicLong SEQUENCE = new AtomicLong();

        final String taskId;
        final String userId;
        final String message;
        final String taskType;
        final String cronExpression;
        final long intervalSeconds;
        final boolean needsProcessing;
        final Instant createdAt;
        final long sequence;
        volatile ScheduledFuture<?> future;

        ReminderTask(String taskId, String userId, String message,
                     String taskType, String cronExpression, long intervalSeconds, boolean needsProcessing) {
            this.taskId = taskId;
            this.userId = userId;
            this.message = message;
            this.taskType = taskType;
            this.cronExpression = cronExpression;
            this.intervalSeconds = intervalSeconds;
            this.needsProcessing = needsProcessing;
            this.createdAt = Instant.now();
            this.sequence = SEQUENCE.incrementAndGet();
        }
    }
}
