package ykd.ykd.exception;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 全局异常处理器 —— 统一捕获所有异常，输出友好的控制台错误提示。
 *
 * <p>使用方式：
 * <pre>
 *   // 包裹一段逻辑
 *   GlobalExceptionHandler.run(() -> { ... });
 *
 *   // 或直接处理异常
 *   GlobalExceptionHandler.handle(e);
 * </pre>
 */
public final class GlobalExceptionHandler {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private GlobalExceptionHandler() {}

    // ==================== 公开接口 ====================

    public static void run(Task task) {
        try {
            task.execute();
        } catch (Exception e) {
            handle(e);
        }
    }

    public static void handle(Throwable e) {
        if (e instanceof BusinessException be) {
            handleBusiness(be);
        } else if (e instanceof SystemException se) {
            handleSystem(se);
        } else {
            handleUnknown(e);
        }
    }

    @FunctionalInterface
    public interface Task {
        void execute() throws Exception;
    }

    // ==================== 内部处理 ====================

    private static void handleBusiness(BusinessException e) {
        divider();
        System.out.println("⚠  业务异常");
        System.out.printf("   错误码 : %s%n", e.getErrorCode());
        System.out.printf("   错误信息: %s%n", e.getMessage());
        divider();
    }

    private static void handleSystem(SystemException e) {
        divider();
        System.out.println("✗  系统异常");
        System.out.printf("   错误码 : %s%n", e.getErrorCode());
        System.out.printf("   错误信息: %s%n", e.getMessage());
        System.out.println("   建议   : 请稍后重试，如持续出现请联系技术支持");
        printStackTrace(e);
        divider();
    }

    private static void handleUnknown(Throwable e) {
        divider();
        System.out.println("✗  未知异常");
        System.out.printf("   类型    : %s%n", e.getClass().getName());
        System.out.printf("   错误信息: %s%n",
                e.getMessage() != null ? e.getMessage() : "(无)");
        System.out.println("   建议   : 请联系技术支持并提供以下堆栈信息");
        printStackTrace(e);
        divider();
    }

    private static void printStackTrace(Throwable e) {
        System.out.println("--- 堆栈跟踪 ---");
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        System.out.println(sw);
    }

    private static void divider() {
        System.out.println("========================================");
    }
}
