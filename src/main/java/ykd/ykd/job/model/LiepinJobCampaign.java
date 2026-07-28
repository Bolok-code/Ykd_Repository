package ykd.ykd.job.model;

import lombok.Data;

@Data
public class LiepinJobCampaign {
    private Long id;
    private String userId;
    private String name;
    private Long resumeId;
    private String deliveryMode;
    private String keyword;
    private String city;
    private Integer minSalaryK;
    private Integer maxSalaryK;
    private Integer minMatchScore;
    private boolean excludeOutsourcing = true;
    private String excludedKeywords;
    private Integer dailyLimit;
    private Integer intervalMinutes;
    private String status;
    private Integer consecutiveFailures;
    private String message;
    private String lastRunAt;
    private String nextRunAt;
    private String createdAt;
    private String updatedAt;
}