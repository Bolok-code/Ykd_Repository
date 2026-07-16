package ykd.ykd.exception;

/**
 * 统一异常基类，所有自定义异常均继承此类。
 * 携带错误码和用户友好的错误消息。
 */
public abstract class BaseException extends RuntimeException {

    /** 错误码 */
    private final String errorCode;

    protected BaseException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected BaseException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    @Override
    public String toString() {
        return String.format("[%s] %s", errorCode, getMessage());
    }
}
