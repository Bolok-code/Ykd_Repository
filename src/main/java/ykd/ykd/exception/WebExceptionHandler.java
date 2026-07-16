package ykd.ykd.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring Web 层全局异常处理 —— 当 REST 接口抛出异常时，
 * 返回结构化的 JSON 错误响应，而非默认的 500 页面。
 */
@RestControllerAdvice
public class WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WebExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException e) {
        log.warn("Web业务异常: [{}] {}", e.getErrorCode(), e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("errorCode", e.getErrorCode());
        body.put("message", e.getMessage());
        body.put("type", "business");
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(SystemException.class)
    public ResponseEntity<Map<String, Object>> handleSystem(SystemException e) {
        log.error("Web系统异常: [{}] {}", e.getErrorCode(), e.getMessage(), e);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("errorCode", e.getErrorCode());
        body.put("message", e.getMessage());
        body.put("type", "system");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnknown(Exception e) {
        log.error("Web未知异常: {}", e.getMessage(), e);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("errorCode", "E500_UNKNOWN");
        body.put("message", "系统内部错误，请联系管理员");
        body.put("type", "unknown");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
