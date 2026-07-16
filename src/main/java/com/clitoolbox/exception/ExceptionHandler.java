package com.clitoolbox.exception;

/**
 * 全局异常处理器 —— 所有未捕获异常的"兜底网"。
 * <p>
 * 确保用户看到的是清晰友好的中文提示，而不是堆栈追踪。
 */
public final class ExceptionHandler {

    private ExceptionHandler() {
    }

    /**
     * 处理异常：根据异常类型输出不同的错误信息，然后以退出码 1 终止。
     *
     * @param context 异常发生的上下文描述（如命令名 "weather"）
     * @param t       捕获到的异常
     */
    public static void handle(String context, Throwable t) {
        String message = format(context, t);
        System.err.println(message);
        System.exit(1);
    }

    /**
     * 将异常格式化为用户友好的错误消息
     */
    public static String format(String context, Throwable t) {
        // 如果是自定义异常，直接取预设的用户消息
        if (t instanceof CliException ce) {
            return "[错误] " + ce.getUserMessage();
        }

        // 根据异常类型匹配常见场景
        String cause = t.getMessage();
        if (cause == null) {
            cause = t.getClass().getSimpleName();
        }

        String hint = switch (t) {
            case java.net.ConnectException e -> "无法连接到服务器，请检查网络连接。";
            case java.net.UnknownHostException e -> "DNS 解析失败，请检查网络或域名配置。";
            case java.io.IOException e -> "I/O 操作失败: " + cause;
            case IllegalArgumentException e -> "参数不合法: " + cause;
            case null, default -> "程序出现意外错误，请重试或联系支持。\n  详情: " + cause;
        };

        return "[错误] " + hint + (context != null ? "\n  位置: " + context : "");
    }
}
