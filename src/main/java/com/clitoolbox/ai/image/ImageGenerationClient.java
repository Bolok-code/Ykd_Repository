package com.clitoolbox.ai.image;

/**
 * 文生图模型抽象。
 */
public interface ImageGenerationClient {

    GeneratedImage generate(String prompt);

    String modelName();
}
