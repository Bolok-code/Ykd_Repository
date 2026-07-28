package ykd.ykd.job.model;

import lombok.Data;

@Data
public class LiepinApplicationRecord {
    private Long id;
    private String userId;
    private Long campaignId;
    private Long taskId;
    private Long postingId;
    private String externalJobKey;
    private String jobName;
    private String companyName;
    private Long resumeId;
    private String deliveryMode;
    private String status;
    private Integer attemptCount;
    private String failureReason;
    private String contactedAt;
    private String resumeSentAt;
    private String createdAt;
    private String updatedAt;
}