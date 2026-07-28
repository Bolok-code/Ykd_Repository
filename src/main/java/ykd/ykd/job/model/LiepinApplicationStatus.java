package ykd.ykd.job.model;

public enum LiepinApplicationStatus {
    PENDING,
    CONTACTING,
    SENDING_RESUME,
    SUCCESS,
    ALREADY_SENT,
    LOGIN_EXPIRED,
    RESUME_NOT_FOUND,
    RESUME_BUTTON_NOT_FOUND,
    UPLOAD_NOT_SUPPORTED,
    CAPTCHA_REQUIRED,
    RISK_CONTROL,
    JOB_EXPIRED,
    FAILED;

    public boolean pausesCampaign() {
        return this == LOGIN_EXPIRED || this == CAPTCHA_REQUIRED || this == RISK_CONTROL;
    }

    public boolean isSuccessful() {
        return this == SUCCESS || this == ALREADY_SENT;
    }
}