package com.clitoolbox.ai.vision;

/**
 * 图片理解模型抽象。
 */
public interface ImageUnderstandingClient {

    String analyze(byte[] imageBytes, String mimeType, String question);

    String modelName();
}
