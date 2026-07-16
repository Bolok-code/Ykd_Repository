package ykd.ykd.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice   // = @ControllerAdvice + @ResponseBody，拦截异常并返回 JSON
public class GlobalExceptionHandler {

    /**
     * 业务异常统一返回：HTTP 400。
     *
     * @param ex 业务异常，包含错误码与消息。
     * @return 响应体：code/message。
     */
    //每个方法上的 @ExceptionHandler(XxxException.class) 表示"我只处理这种异常"
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", ex.getErrorCode().getCode());
        body.put("message", ex.getMessage());
        return ResponseEntity.badRequest().body(body);
    }
}