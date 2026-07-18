package com.clitoolbox.ai.speech;

/**
 * 将语音合成结果转换为消息渠道需要的编码格式。
 */
public interface SpeechEncoder {

    GeneratedSpeech encode(GeneratedSpeech source);
}
