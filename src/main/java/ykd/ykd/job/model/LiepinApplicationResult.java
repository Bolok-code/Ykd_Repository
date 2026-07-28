package ykd.ykd.job.model;

public record LiepinApplicationResult(
        boolean success,
        boolean needsUserAction,
        LiepinApplicationStatus status,
        String message) {

    public static LiepinApplicationResult success(String message) {
        return new LiepinApplicationResult(true, false, LiepinApplicationStatus.SUCCESS, message);
    }

    public static LiepinApplicationResult alreadySent(String message) {
        return new LiepinApplicationResult(true, false, LiepinApplicationStatus.ALREADY_SENT, message);
    }

    public static LiepinApplicationResult needsUserAction(String message) {
        return needsUserAction(LiepinApplicationStatus.FAILED, message);
    }

    public static LiepinApplicationResult needsUserAction(LiepinApplicationStatus status, String message) {
        return new LiepinApplicationResult(false, true, status, message);
    }

    public static LiepinApplicationResult failed(String message) {
        return failed(LiepinApplicationStatus.FAILED, message);
    }

    public static LiepinApplicationResult failed(LiepinApplicationStatus status, String message) {
        return new LiepinApplicationResult(false, false, status, message);
    }
}