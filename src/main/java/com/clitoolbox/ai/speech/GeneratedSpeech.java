package com.clitoolbox.ai.speech;

/**
 * 已生成并带有 iLink 发送元数据的语音。
 *
 * @param data 语音二进制数据
 * @param fileName 上传到 iLink 时使用的文件名
 * @param playTimeMs 播放时长，单位毫秒
 * @param sampleRate 采样率，单位 Hz
 * @param encodeType iLink 语音编码类型，1 表示 PCM，6 表示 SILK
 * @param bitsPerSample 位深
 */
public record GeneratedSpeech(
        byte[] data,
        String fileName,
        int playTimeMs,
        int sampleRate,
        int encodeType,
        int bitsPerSample) {
}
