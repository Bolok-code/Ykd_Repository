package com.clitoolbox.ai.speech;

/**
 * 将机器人生成的文字答案转换为可直接发送的语音数据。
 */
public interface SpeechSynthesisClient {

    GeneratedSpeech synthesize(String text);

    String modelName();
}
