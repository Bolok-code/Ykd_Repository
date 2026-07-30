package ykd.ykd.llm.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import ykd.ykd.exception.ErrorCode;
import ykd.ykd.task.UnifiedReminderManager;
import ykd.ykd.processor.UserContext;

@Slf4j
@Component
public class ReminderTools {

    private final UnifiedReminderManager unifiedReminderManager;
    private final UserContext userContext;

    public ReminderTools(UnifiedReminderManager unifiedReminderManager,
                         UserContext userContext) {
        this.unifiedReminderManager = unifiedReminderManager;
        this.userContext = userContext;
    }

    @Tool(description = "设置提醒。一次性（10分钟后提醒我开会）、每日（每天早上8点提醒我打卡）、每周（每周日早上8点提醒我周报）、间隔重复（每10秒/每2小时提醒我喝水）")
    public String setReminder(
            @ToolParam(description = "提醒时间，如'10分钟后'、'每天早上8点'、'每周日早上8点'、'每10秒'、'每隔2小时'") String timeExpression,
            @ToolParam(description = "提醒内容") String message) {
        String userId = userContext.getCurrentUserId();
        if (userId == null) {
            log.error("[ReminderTools] setReminder 失败: userId 为空");
            return "❌ " + ErrorCode.MESSAGE_PROCESS_FAILED.getDefaultMessage();
        }
        log.info("[ReminderTools] setReminder: userId={}, time={}, msg={}", userId, timeExpression, message);
        boolean needsProcessing = looksLikeQuery(message);
        try {
            if (isWeekly(timeExpression)) {
                return unifiedReminderManager.scheduleWeekly(userId, message, timeExpression, needsProcessing);
            }
            if (timeExpression.contains("每天") || timeExpression.contains("每日")) {
                return unifiedReminderManager.scheduleDaily(userId, message, timeExpression, needsProcessing);
            }
            if (isInterval(timeExpression)) {
                return unifiedReminderManager.scheduleInterval(userId, message, timeExpression, needsProcessing);
            }
            return unifiedReminderManager.scheduleOnce(userId, message, timeExpression, needsProcessing);
        } catch (Exception e) {
            log.error("[ReminderTools] setReminder 异常: userId={}, time={}, msg={}",
                    userId, timeExpression, message, e);
            return "❌ " + ErrorCode.REMINDER_SCHEDULE_FAILED.getDefaultMessage();
        }
    }

    private boolean isWeekly(String timeExpression) {
        return timeExpression.contains("每周");
    }

    private boolean isInterval(String timeExpression) {
        return timeExpression.startsWith("每") && !timeExpression.contains("每天")
                && !timeExpression.contains("每日") && !timeExpression.contains("每周");
    }

    static boolean looksLikeQuery(String message) {
        String[] queryKeywords = {"天气", "搜索", "新闻", "查询", "告诉我", "查"};
        for (String kw : queryKeywords) {
            if (message.contains(kw)) return true;
        }
        return false;
    }

    @Tool(description = "查看所有待执行的提醒（包括单次、每日、每周和间隔提醒）")
    public String listReminders() {
        String userId = userContext.getCurrentUserId();
        if (userId == null) {
            log.error("[ReminderTools] listReminders 失败: userId 为空");
            return "❌ " + ErrorCode.MESSAGE_PROCESS_FAILED.getDefaultMessage();
        }
        log.info("[ReminderTools] listReminders: userId={}", userId);
        try {
            return unifiedReminderManager.listTasks(userId);
        } catch (Exception e) {
            log.error("[ReminderTools] listReminders 异常: userId={}", userId, e);
            return "❌ " + ErrorCode.REMINDER_LIST_FAILED.getDefaultMessage();
        }
    }

    @Tool(description = "取消指定提醒，序号来自 listReminders 返回的列表")
    public String cancelReminder(
            @ToolParam(description = "提醒序号，如1、2、3") int index) {
        String userId = userContext.getCurrentUserId();
        if (userId == null) {
            log.error("[ReminderTools] cancelReminder 失败: userId 为空");
            return "❌ " + ErrorCode.MESSAGE_PROCESS_FAILED.getDefaultMessage();
        }
        log.info("[ReminderTools] cancelReminder: userId={}, index={}", userId, index);
        try {
            return unifiedReminderManager.cancelByIndex(userId, index);
        } catch (Exception e) {
            log.error("[ReminderTools] cancelReminder 异常: userId={}, index={}", userId, index, e);
            return "❌ " + ErrorCode.REMINDER_CANCEL_FAILED.getDefaultMessage();
        }
    }

    @Deprecated
    @Tool(description = "查看所有间隔重复提醒（已合并到 listReminders，请优先使用 listReminders）")
    public String listIntervalReminders() {
        return listReminders();
    }

    @Deprecated
    @Tool(description = "取消指定间隔提醒（已合并到 cancelReminder，请优先使用 cancelReminder）")
    public String cancelIntervalReminder(
            @ToolParam(description = "提醒序号，如1、2、3") int index) {
        return cancelReminder(index);
    }
}
