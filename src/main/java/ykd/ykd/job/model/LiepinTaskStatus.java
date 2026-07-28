package ykd.ykd.job.model;

public enum LiepinTaskStatus {
    CREATED,
    SEARCHING,
    ANALYZING,
    WAITING_CONFIRMATION,
    SUBMITTING,
    SUCCEEDED,
    NEEDS_USER_ACTION,
    FAILED,
    CANCELLED
}