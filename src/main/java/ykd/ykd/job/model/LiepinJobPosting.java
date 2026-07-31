package ykd.ykd.job.model;

import lombok.Data;

/**
 * 职位信息实体，映射表 liepin_job_posting。
 *
 * <p>存储从猎聘搜索到的职位详情，包含 AI 匹配后的评分、理由和招呼语。
 * 职位来源有两路：API 响应解析（优先）和 DOM 降级抓取。</p>
 */
@Data
public class LiepinJobPosting {
    private Long id;
    /** 关联的搜索任务 ID（liepin_job_task） */
    private Long taskId;
    /** 猎聘内部职位 ID */
    private String externalJobId;
    /** 职位名称 */
    private String jobName;
    /** 公司名称 */
    private String companyName;
    /** 公司行业 */
    private String companyIndustry;
    /** 公司规模 */
    private String companyScale;
    /** 工作城市 */
    private String city;
    /** 薪资描述原文，如 "15K-25K" */
    private String salary;
    /** 学历要求 */
    private String education;
    /** 工作经验要求 */
    private String experience;
    /** 招聘者姓名 */
    private String recruiterName;
    /** 招聘者职位 */
    private String recruiterTitle;
    /** 招聘者 IM ID（猎聘内部聊天系统标识） */
    private String recruiterImId;
    /** 职位发布时间 */
    private String publishedAt;
    /** 职位描述（拼接学历+经验+标签） */
    private String description;
    /** 职位详情页 URL */
    private String jobUrl;
    /** AI 匹配分数（0-100） */
    private Integer matchScore;
    /** AI 匹配理由 */
    private String matchReason;
    /** AI 生成的打招呼文案 */
    private String greeting;
    /** 处理状态，默认 CANDIDATE */
    private String status = "CANDIDATE";
    /** 记录创建时间 */
    private String createdAt;
}
