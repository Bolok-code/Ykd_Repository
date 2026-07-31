package ykd.ykd.job.model;

/**
 * 自动投递计划的状态。
 *
 * <h3>状态流转</h3>
 * <pre>
 * CREATED → RUNNING → PAUSED / STOPPED
 *                    → LOGIN_REQUIRED（token 过期，下次扫描自动恢复）
 *                    → RISK_CONTROL（触发风控，需用户处理）
 *                    → FAILED（严重错误，如简历丢失）
 * RUNNING → PAUSED    （连续失败达到上限，自动暂停）
 * </pre>
 */
public enum LiepinCampaignStatus {
    /** 已创建但尚未启动，等待用户确认 */
    CREATED,
    /** 正在运行，每分钟被调度器扫描 */
    RUNNING,
    /** 已暂停，可恢复为 RUNNING */
    PAUSED,
    /** 已停止，不可恢复 */
    STOPPED,
    /** 登录过期，等待用户重新扫码 */
    LOGIN_REQUIRED,
    /** 触发猎聘风控/验证码 */
    RISK_CONTROL,
    /** 致命错误，需人工处理 */
    FAILED
}
