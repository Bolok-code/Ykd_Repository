package com.clitoolbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 微信语音回复投递策略。
 *
 * @param sendFileCopyAfterNative iLink 原生语音没有设备回执，提交后是否追加 WAV 文件
 */
@ConfigurationProperties(prefix = "app.ilink.voice")
public record VoiceReplyProperties(
        boolean sendFileCopyAfterNative) {
}
