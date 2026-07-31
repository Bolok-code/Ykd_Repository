package ykd.ykd.job.model;

/**
 * 投递状态，记录单次投递操作所处的阶段或结果。
 *
 * <h3>生命周期</h3>
 * {@code PENDING → CONTACTING → SENDING_RESUME → SUCCESS / FAILED}
 */
public enum LiepinApplicationStatus {
    /** 待处理，尚未开始 */
    PENDING,
    /** 正在联系招聘者（点"聊一聊"） */
    CONTACTING,
    /** 正在发送简历 */
    SENDING_RESUME,
    /** 投递成功 */
    SUCCESS,
    /** 已经投递过（去重，视为成功） */
    ALREADY_SENT,
    /** 登录过期，调用方应暂停计划并提示用户重新登录 */
    LOGIN_EXPIRED,
    /** 未找到简历 */
    RESUME_NOT_FOUND,
    /** 页面上找不到发送简历按钮 */
    RESUME_BUTTON_NOT_FOUND,
    /** 当前投递方式不支持（如 ATTACHMENT 但文件格式不对） */
    UPLOAD_NOT_SUPPORTED,
    /** 需要用户完成验证码 */
    CAPTCHA_REQUIRED,
    /** 触发猎聘风控 */
    RISK_CONTROL,
    /** 职位已过期/下架 */
    JOB_EXPIRED,
    /** 投递失败（通用） */
    FAILED;

    /**
     * 是否需要暂停整个投递计划。
     * 登录过期、验证码、风控属于需要用户干预的阻断性错误。
     */
    public boolean pausesCampaign() {
        return this == LOGIN_EXPIRED || this == CAPTCHA_REQUIRED || this == RISK_CONTROL;
    }

    /** 投递是否视为成功（含已投递过的去重场景）。 */
    public boolean isSuccessful() {
        return this == SUCCESS || this == ALREADY_SENT;
    }
}
