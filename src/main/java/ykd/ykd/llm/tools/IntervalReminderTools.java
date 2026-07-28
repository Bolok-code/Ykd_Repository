package ykd.ykd.llm.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import ykd.ykd.exception.ErrorCode;
import ykd.ykd.task.IntervalReminderManager;
import ykd.ykd.processor.UserContext;

@Slf4j
@Component
public class IntervalReminderTools {

    private final IntervalReminderManager intervalReminderManager;
    private final UserContext userContext;

    public IntervalReminderTools(IntervalReminderManager intervalReminderManager, UserContext userContext) {
        this.intervalReminderManager = intervalReminderManager;
        this.userContext = userContext;
    }

    @Tool(description = "设置间隔重复提醒。当用户说'每隔X小时/分钟提醒我做某事'、'每X时间重复提醒'时调用此工具")
    public String setIntervalReminder(
            @ToolParam(description = "间隔时间，如'每隔2小时'、'每30分钟'、'每隔1小时'") String intervalExpression,
            @ToolParam(description = "提醒内容") String message) {
        String userId = userContext.getCurrentUserId();
        if (userId == null) {
            log.error("[IntervalReminderTools] setIntervalReminder 失败: userId 为空");
            return "❌ " + ErrorCode.MESSAGE_PROCESS_FAILED.getDefaultMessage();
        }
        log.info("[IntervalReminderTools] setIntervalReminder: userId={}, interval={}, msg={}",
                userId, intervalExpression, message);
        try {
            return intervalReminderManager.scheduleInterval(userId, message, intervalExpression);
        } catch (Exception e) {
            log.error("[IntervalReminderTools] setIntervalReminder 异常: userId={}, interval={}, msg={}",
                    userId, intervalExpression, message, e);
            return "❌ " + ErrorCode.REMINDER_SCHEDULE_FAILED.getDefaultMessage();
        }
    }

    @Tool(description = "查看所有间隔重复提醒")
    public String listIntervalReminders() {
        String userId = userContext.getCurrentUserId();
        if (userId == null) {
            log.error("[IntervalReminderTools] listIntervalReminders 失败: userId 为空");
            return "❌ " + ErrorCode.MESSAGE_PROCESS_FAILED.getDefaultMessage();
        }
        try {
            return intervalReminderManager.listTasks(userId);
        } catch (Exception e) {
            log.error("[IntervalReminderTools] listIntervalReminders 异常: userId={}", userId, e);
            return "❌ " + ErrorCode.REMINDER_LIST_FAILED.getDefaultMessage();
        }
    }

    @Tool(description = "取消指定间隔提醒，序号来自 listIntervalReminders 返回的列表")
    public String cancelIntervalReminder(
            @ToolParam(description = "提醒序号，如1、2、3") int index) {
        String userId = userContext.getCurrentUserId();
        if (userId == null) {
            log.error("[IntervalReminderTools] cancelIntervalReminder 失败: userId 为空");
            return "❌ " + ErrorCode.MESSAGE_PROCESS_FAILED.getDefaultMessage();
        }
        try {
            return intervalReminderManager.cancelByIndex(userId, index);
        } catch (Exception e) {
            log.error("[IntervalReminderTools] cancelIntervalReminder 异常: userId={}, index={}", userId, index, e);
            return "❌ " + ErrorCode.REMINDER_CANCEL_FAILED.getDefaultMessage();
        }
    }
}
