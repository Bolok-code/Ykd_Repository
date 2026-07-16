package ykd.ykd.exception;

/**
 * 系统异常 —— 由外部依赖调用失败、IO错误等非预期场景抛出。
 * 对用户展示模糊化后的提示，避免暴露内部细节。
 */
public class SystemException extends BaseException {

    public SystemException(String errorCode, String message) {
        super(errorCode, message);
    }

    public SystemException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    /**
     * 快捷构造：网络/HTTP 调用失败
     */
    public static SystemException httpError(String detail, Throwable cause) {
        return new SystemException("E500_HTTP", String.format("外部服务调用失败: %s", detail), cause);
    }

    /**
     * 快捷构造：IO 异常
     */
    public static SystemException ioError(String detail, Throwable cause) {
        return new SystemException("E500_IO", String.format("IO 操作失败: %s", detail), cause);
    }

    /**
     * 快捷构造：未知系统错误
     */
    public static SystemException unknown(Throwable cause) {
        return new SystemException("E500_UNKNOWN", "系统内部错误，请联系管理员", cause);
    }
}
