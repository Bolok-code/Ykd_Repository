package ykd.ykd.llm.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import ykd.ykd.exception.ErrorCode;
import ykd.ykd.processor.ReminderTaskManager;
import ykd.ykd.processor.UserContext;

@Slf4j
@Component
public class ReminderTools {

    private final ReminderTaskManager reminderTaskManager;
    private final UserContext userContext;

    public ReminderTools(ReminderTaskManager reminderTaskManager, UserContext userContext) {
        this.reminderTaskManager = reminderTaskManager;
        this.userContext = userContext;
    }

    @Tool(description = "设置定时提醒。当用户要求定时提醒、延迟通知、闹钟时调用此工具")
    public String setReminder(
            @ToolParam(description = "提醒时间，如'10分钟后'、'2小时后'、'每天早上8点'") String timeExpression,
            @ToolParam(description = "提醒内容") String message) {
        String userId = userContext.getCurrentUserId();
        if (userId == null) {
            log.error("[ReminderTools] setReminder 失败: userId 为空");
            return "❌ " + ErrorCode.MESSAGE_PROCESS_FAILED.getDefaultMessage();
        }
        log.info("[ReminderTools] setReminder: userId={}, time={}, msg={}", userId, timeExpression, message);
        try {
            if (timeExpression.contains("每天")) {
                return reminderTaskManager.scheduleDaily(userId, message, timeExpression);
            }
            return reminderTaskManager.scheduleOnce(userId, message, timeExpression);
        } catch (Exception e) {
            log.error("[ReminderTools] setReminder 异常: userId={}, time={}, msg={}",
                    userId, timeExpression, message, e);
            return "❌ " + ErrorCode.REMINDER_SCHEDULE_FAILED.getDefaultMessage();
        }
    }

    @Tool(description = "查看所有待执行的提醒")
    public String listReminders() {
        String userId = userContext.getCurrentUserId();
        if (userId == null) {
            log.error("[ReminderTools] listReminders 失败: userId 为空");
            return "❌ " + ErrorCode.MESSAGE_PROCESS_FAILED.getDefaultMessage();
        }
        log.info("[ReminderTools] listReminders: userId={}", userId);
        try {
            return reminderTaskManager.listTasks(userId);
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
            return reminderTaskManager.cancelByIndex(userId, index);
        } catch (Exception e) {
            log.error("[ReminderTools] cancelReminder 异常: userId={}, index={}", userId, index, e);
            return "❌ " + ErrorCode.REMINDER_CANCEL_FAILED.getDefaultMessage();
        }
    }
}
