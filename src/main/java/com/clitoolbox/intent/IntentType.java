package com.clitoolbox.intent;

/**
 * 用户文字表达的业务意图。
 *
 * <p>图片、语音等入站消息格式由 iLink SDK 的消息字段判断，不交给大模型猜测。
 */
public enum IntentType {
    TEXT_CHAT,
    WEATHER_QUERY,
    IMAGE_GENERATION
}
