package com.clitoolbox.exception;

/**
 * 错误类型枚举 —— 对异常进行分类，便于根据不同类别做不同的处理
 */
public enum ErrorCode {

    /** 网络 / API 连接问题 */
    NETWORK_ERROR,

    /** 用户输入不合法（空城市名、无效参数等） */
    INVALID_INPUT,

    /** 配置缺失或错误（API Key 未设置等） */
    CONFIG_ERROR,

    /** 功能尚未实现 */
    NOT_IMPLEMENTED,

    /** 未归类 / 意外错误 */
    UNKNOWN
}
