package ykd.ykd.exception;

/**
 * 业务异常 —— 由业务逻辑校验失败、参数非法等场景主动抛出。
 * 代表"预期内"的错误，消息对用户友好。
 */
public class BusinessException extends BaseException {

    public BusinessException(String errorCode, String message) {
        super(errorCode, message);
    }

    public BusinessException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    /**
     * 快捷构造：参数校验失败
     */
    public static BusinessException invalidParam(String paramName, String detail) {
        return new BusinessException("E400", String.format("参数校验失败 [%s]: %s", paramName, detail));
    }

    /**
     * 快捷构造：资源不存在
     */
    public static BusinessException notFound(String resource, String id) {
        return new BusinessException("E404", String.format("%s 不存在: %s", resource, id));
    }

    /**
     * 快捷构造：操作不允许
     */
    public static BusinessException notAllowed(String detail) {
        return new BusinessException("E403", String.format("操作不允许: %s", detail));
    }
}
