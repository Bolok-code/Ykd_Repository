package com.clitoolbox.intent;

/**
 * 将自然语言转换为项目内部的结构化意图。
 *
 * <p>业务层只依赖这个接口，后续更换豆包模型或其他供应商时无需修改消息处理流程。
 */
public interface IntentClassifier {

    IntentDecision classify(String text);

    default IntentDecision classify(String text, IntentContext context) {
        return classify(text);
    }

    String modelName();
}
