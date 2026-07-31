package ykd.ykd.job.model;

/**
 * 单次投递操作的结果。
 *
 * @param success         投递是否成功
 * @param needsUserAction 是否需要用户手动干预（如扫码、验证码）
 * @param status          详细的投递状态
 * @param message         结果描述文案，可直接展示给用户
 */
public record LiepinApplicationResult(
        boolean success,
        boolean needsUserAction,
        LiepinApplicationStatus status,
        String message) {

    /** 投递成功 */
    public static LiepinApplicationResult success(String message) {
        return new LiepinApplicationResult(true, false, LiepinApplicationStatus.SUCCESS, message);
    }

    /** 职位已投递过，视为成功（去重） */
    public static LiepinApplicationResult alreadySent(String message) {
        return new LiepinApplicationResult(true, false, LiepinApplicationStatus.ALREADY_SENT, message);
    }

    /** 投递失败，需要用户操作 */
    public static LiepinApplicationResult needsUserAction(String message) {
        return needsUserAction(LiepinApplicationStatus.FAILED, message);
    }

    /** 投递失败（指定状态），需要用户操作 */
    public static LiepinApplicationResult needsUserAction(LiepinApplicationStatus status, String message) {
        return new LiepinApplicationResult(false, true, status, message);
    }

    /** 投递失败，无需用户操作（系统可自动重试） */
    public static LiepinApplicationResult failed(String message) {
        return failed(LiepinApplicationStatus.FAILED, message);
    }

    /** 投递失败（指定状态），无需用户操作 */
    public static LiepinApplicationResult failed(LiepinApplicationStatus status, String message) {
        return new LiepinApplicationResult(false, false, status, message);
    }
}
