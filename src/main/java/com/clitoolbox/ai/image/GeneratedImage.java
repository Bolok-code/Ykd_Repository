package com.clitoolbox.ai.image;

/**
 * 已下载、可直接通过 iLink SDK 发送的生成图片。
 */
public record GeneratedImage(byte[] data, String fileName, String mimeType) {
}
