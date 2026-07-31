package ykd.ykd.memory.model;

import lombok.Data;

@Data
public class ReminderTaskEntity {
    private Long id;
    private String taskId;
    private String userId;
    private String message;
    private String timeExpression;
    private String taskType;
    private Integer intervalSeconds;
    private String dailyTime;
    private Integer delaySeconds;
    private String cronExpression;
    private Integer needsProcessing;
    private String status;
    private String createdAt;
}
