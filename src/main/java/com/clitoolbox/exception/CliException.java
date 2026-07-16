package com.clitoolbox.exception;

/**
 * CLI-Toolbox 自定义运行时异常。
 * <p>
 * 相较于直接抛 {@link RuntimeException}，自定义异常携带了
 * {@link ErrorCode 错误类型} 和面向用户的友好提示信息。
 */
public class CliException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String userMessage;

    // ---- 构造器 ----

    public CliException(ErrorCode errorCode, String userMessage) {
        super(userMessage);
        this.errorCode = errorCode;
        this.userMessage = userMessage;
    }

    public CliException(ErrorCode errorCode, String userMessage, Throwable cause) {
        super(userMessage, cause);
        this.errorCode = errorCode;
        this.userMessage = userMessage;
    }

    public CliException(String userMessage) {
        this(ErrorCode.UNKNOWN, userMessage);
    }

    // ---- getter ----

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getUserMessage() {
        return userMessage;
    }
}
