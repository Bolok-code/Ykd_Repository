package com.example.wechatbot.config;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class LoggingAspect {
    private static final Logger log = LoggerFactory.getLogger(LoggingAspect.class);

    @Around("execution(* com.example.wechatbot.service.*.*(..))")
    public Object logService(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        log.info("[REQ] {}.{}(..) args={}", pjp.getTarget().getClass().getSimpleName(),
                pjp.getSignature().getName(), pjp.getArgs());
        try {
            Object result = pjp.proceed();
            log.info("[RES] {}.{}(..) time={}ms result={}", pjp.getTarget().getClass().getSimpleName(),
                    pjp.getSignature().getName(), System.currentTimeMillis() - start,
                    result != null ? result.toString().substring(0, Math.min(result.toString().length(), 100)) : "null");
            return result;
        } catch (Throwable t) {
            log.error("[ERR] {}.{}(..) time={}ms error={}", pjp.getTarget().getClass().getSimpleName(),
                    pjp.getSignature().getName(), System.currentTimeMillis() - start, t.getMessage());
            throw t;
        }
    }
}
