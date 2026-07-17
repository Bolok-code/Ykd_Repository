package com.clitoolbox.ai;

import java.util.List;

/**
 * 大模型聊天客户端抽象，业务层不直接依赖具体模型供应商。
 */
public interface AiChatClient {

    String chat(List<ChatMessage> messages);

    String modelName();
}
