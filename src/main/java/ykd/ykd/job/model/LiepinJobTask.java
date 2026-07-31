package ykd.ykd.job.model;

import lombok.Data;

/**
 * 搜索任务实体，映射表 liepin_job_task。
 *
 * <p>表示一次手动或自动的职位搜索请求，携带搜索参数和当前状态。
 * 手动搜索完成后状态为 {@code WAITING_CONFIRMATION}，等待用户选择候选职位。</p>
 */
@Data
public class LiepinJobTask {
    private Long id;
    /** 微信用户 ID */
    private String userId;
    /** 搜索关键词 */
    private String keyword;
    /** 目标城市 */
    private String city;
    /** 最低月薪（K） */
    private Integer minSalaryK;
    /** 最高月薪（K） */
    private Integer maxSalaryK;
    /** 是否排除外包公司 */
    private boolean excludeOutsourcing = true;
    /** 任务状态，参见 {@link LiepinTaskStatus} */
    private String status;
    /** 状态描述消息 */
    private String message;
    /** 任务创建时间 */
    private String createdAt;
    /** 任务更新时间 */
    private String updatedAt;
}
