package ykd.ykd.job.model;

import lombok.Data;

/**
 * 投递记录实体，映射表 liepin_application_record。
 *
 * <p>记录每一次对特定职位的投递操作及其结果。
 * 通过 {@code (user_id, external_job_key)} 唯一约束防止重复投递。</p>
 */
@Data
public class LiepinApplicationRecord {
    private Long id;
    /** 微信用户 ID */
    private String userId;
    /** 关联的投递计划 ID（liepin_job_campaign） */
    private Long campaignId;
    /** 关联的搜索任务 ID（liepin_job_task） */
    private Long taskId;
    /** 关联的职位 ID（liepin_job_posting） */
    private Long postingId;
    /** 职位唯一键，去重用：{@code "id:<externalId>"} 或 {@code "hash:<SHA256>"} */
    private String externalJobKey;
    /** 职位名称 */
    private String jobName;
    /** 公司名称 */
    private String companyName;
    /** 关联的简历 ID（liepin_resume） */
    private Long resumeId;
    /** 投递方式：ONLINE / ATTACHMENT */
    private String deliveryMode;
    /** 投递状态，参见 {@link LiepinApplicationStatus} */
    private String status;
    /** 尝试次数，重试时递增 */
    private Integer attemptCount;
    /** 失败原因 */
    private String failureReason;
    /** 联系时间（点"聊一聊"的时间） */
    private String contactedAt;
    /** 简历发送时间 */
    private String resumeSentAt;
    /** 记录创建时间 */
    private String createdAt;
    /** 记录更新时间 */
    private String updatedAt;
}
