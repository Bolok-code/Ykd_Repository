package com.clitoolbox.ai.speech;

/**
 * 百炼生成的、可作为普通文件发送的音频。
 *
 * @param data 音频二进制数据
 * @param fileName 上传到 iLink 时使用的文件名
 */
public record GeneratedSpeech(
        byte[] data,
        String fileName) {
}
