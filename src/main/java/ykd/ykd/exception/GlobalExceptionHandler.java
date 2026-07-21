package ykd.ykd.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理。
 *
 * <p>{@code @RestControllerAdvice} 仅拦截 REST 请求（{@code /weather/search} 等）。
 * 微信机器人消息处理路径由各组件自行 catch 并返回用户友好文案。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException ex) {
        log.warn("[REST] 业务异常: code={}, message={}", ex.getErrorCode().getCode(), ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of(
                "code", ex.getErrorCode().getCode(),
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnknown(Exception ex) {
        log.error("[REST] 未知异常", ex);
        return ResponseEntity.internalServerError().body(Map.of(
                "code", "INTERNAL_ERROR",
                "message", "服务器内部错误"
        ));
    }

    /**
     * 将异常转为用户可见提示文案（机器人路径用）。
     */
    public static String toUserMessage(Throwable e) {
        if (e instanceof BusinessException be) {
            return be.getMessage();
        }
        return "❌ " + ErrorCode.MESSAGE_PROCESS_FAILED.getDefaultMessage();
    }
}
