package ykd.ykd.job.model;

public record LiepinApplicationResult(boolean success, boolean needsUserAction, String message) {
    public static LiepinApplicationResult success(String message) {
        return new LiepinApplicationResult(true, false, message);
    }

    public static LiepinApplicationResult needsUserAction(String message) {
        return new LiepinApplicationResult(false, true, message);
    }

    public static LiepinApplicationResult failed(String message) {
        return new LiepinApplicationResult(false, false, message);
    }
}