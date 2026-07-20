package com.clitoolbox.conversation;

import com.clitoolbox.ai.ChatMessage;
import java.util.List;

/**
 * 对话历史存储抽象。
 */
public interface ConversationRepository {

    List<ChatMessage> findByUserId(String userId);

    void appendTurn(String userId,
                ChatMessage userMessage,
                ChatMessage assistantMessage
                );

    void clear(String userId);
}
