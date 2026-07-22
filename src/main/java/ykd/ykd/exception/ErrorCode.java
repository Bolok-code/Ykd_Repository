package ykd.ykd.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {

    // 天气
    API_ERROR("API_ERROR", "高德API调用失败"),
    CITY_NOT_FOUND("CITY_NOT_FOUND", "未找到对应城市"),
    NETWORK_ERROR("NETWORK_ERROR", "网络请求失败"),
    ANALYSIS_ERROR("ANALYSIS_ERROR", "解析天气数据失败"),
    AI_WEATHER_FAILED("AI_WEATHER_FAILED", "天气查询失败"),

    // AI 对话
    AI_CALL_FAILED("AI_CALL_FAILED", "AI 服务暂时不可用，请稍后重试"),

    // 图片
    IMAGE_GENERATE_FAILED("IMAGE_GENERATE_FAILED", "图片生成失败"),
    IMAGE_DOWNLOAD_FAILED("IMAGE_DOWNLOAD_FAILED", "图片下载失败"),
    CDN_DOWNLOAD_FAILED("CDN_DOWNLOAD_FAILED", "CDN 图片下载失败"),

    // 视频
    VIDEO_SUBMIT_FAILED("VIDEO_SUBMIT_FAILED", "视频任务提交失败"),
    VIDEO_POLL_FAILED("VIDEO_POLL_FAILED", "视频状态查询失败"),
    VIDEO_DOWNLOAD_FAILED("VIDEO_DOWNLOAD_FAILED", "视频下载失败"),

    // 语音
    TTS_SYNTHESIS_FAILED("TTS_SYNTHESIS_FAILED", "语音合成失败"),
    STT_RECOGNITION_FAILED("STT_RECOGNITION_FAILED", "语音识别失败"),

    // 提醒
    REMINDER_SCHEDULE_FAILED("REMINDER_SCHEDULE_FAILED", "提醒设置失败"),
    REMINDER_CANCEL_FAILED("REMINDER_CANCEL_FAILED", "提醒取消失败"),
    REMINDER_LIST_FAILED("REMINDER_LIST_FAILED", "提醒查询失败"),

    // 消息处理
    UNSUPPORTED_MESSAGE("UNSUPPORTED_MESSAGE", "不支持的消息类型"),
    MESSAGE_PROCESS_FAILED("MESSAGE_PROCESS_FAILED", "消息处理失败");

    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }
}
