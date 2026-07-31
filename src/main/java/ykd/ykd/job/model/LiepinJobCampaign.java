package ykd.ykd.job.model;

import lombok.Data;

/**
 * 自动投递计划实体，映射表 liepin_job_campaign。
 *
 * <p>用户创建一个计划后需明确确认才会启动（CREATED → RUNNING）。
 * 启动后由 {@code LiepinCampaignScheduler} 每分钟扫描到期计划并执行投递。
 * 每日限额、匹配分数、间隔时间等参数在创建时设定。</p>
 */
@Data
public class LiepinJobCampaign {
    private Long id;
    /** 微信用户 ID */
    private String userId;
    /** 计划名称，如"杭州-Java后端自动投递" */
    private String name;
    /** 关联的简历 ID（liepin_resume） */
    private Long resumeId;
    /** 简历投递方式：ONLINE / ATTACHMENT / AUTO */
    private String deliveryMode;
    /** 搜索关键词 */
    private String keyword;
    /** 目标城市 */
    private String city;
    /** 最低月薪（K），可为空 */
    private Integer minSalaryK;
    /** 最高月薪（K），可为空 */
    private Integer maxSalaryK;
    /** 最低 AI 匹配分数（0-100），低于此分数的职位不投递 */
    private Integer minMatchScore;
    /** 是否排除外包公司 */
    private boolean excludeOutsourcing = true;
    /** 额外排除的关键词（逗号分隔），命中则不投递 */
    private String excludedKeywords;
    /** 每日投递上限 */
    private Integer dailyLimit;
    /** 两次投递之间的最小间隔（分钟） */
    private Integer intervalMinutes;
    /** 计划状态，参见 {@link LiepinCampaignStatus} */
    private String status;
    /** 连续失败次数，达到阈值自动暂停 */
    private Integer consecutiveFailures;
    /** 状态变更消息 */
    private String message;
    /** 上次执行时间 */
    private String lastRunAt;
    /** 下次计划执行时间，调度器据此判断是否到期 */
    private String nextRunAt;
    /** 计划创建时间 */
    private String createdAt;
    /** 计划更新时间 */
    private String updatedAt;
}
