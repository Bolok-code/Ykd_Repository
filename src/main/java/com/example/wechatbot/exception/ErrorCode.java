package com.example.wechatbot.exception;

public enum ErrorCode {
    SYSTEM_ERROR(500, "系统内部错误，请稍后再试"),
    AI_SERVICE_ERROR(5001, "AI服务调用失败"),
    WEATHER_SERVICE_ERROR(5002, "天气查询服务异常"),
    WECHAT_SERVICE_ERROR(5003, "微信消息服务异常"),
    ILINK_ERROR(5004, "iLink连接异常"),
    INVALID_PARAM(4001, "参数无效"),
    RATE_LIMIT(429, "请求过于频繁，请稍后重试");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
}
