package ykd.ykd.job.model;

/**
 * 搜索任务的状态。
 *
 * <h3>手动搜索生命周期</h3>
 * {@code CREATED → SEARCHING → ANALYZING → WAITING_CONFIRMATION → SUBMITTING → SUCCEEDED / FAILED}
 *
 * <h3>自动投递搜索生命周期</h3>
 * {@code CREATED → SEARCHING → ANALYZING → SUBMITTING（逐个投递）→ SUCCEEDED}
 */
public enum LiepinTaskStatus {
    /** 已创建，等待执行 */
    CREATED,
    /** 正在猎聘搜索职位 */
    SEARCHING,
    /** 正在 AI 匹配评分 */
    ANALYZING,
    /** 等待用户从候选列表中选择 */
    WAITING_CONFIRMATION,
    /** 正在提交投递 */
    SUBMITTING,
    /** 任务成功完成 */
    SUCCEEDED,
    /** 需要用户操作（如登录、验证码） */
    NEEDS_USER_ACTION,
    /** 任务失败 */
    FAILED,
    /** 用户取消 */
    CANCELLED
}
