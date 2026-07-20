package com.clitoolbox.conversation;

import com.clitoolbox.ai.ChatMessage;
import java.util.List;
import java.util.Optional;

/**
 * 对话历史存储抽象。
 */
public interface ConversationRepository {

    List<ChatMessage> findByUserId(String userId);

    void appendTurn(String userId,
                ConversationTurn   turn
                );

    void clear(String userId);
    Optional<ConversationContext> findLatestContext(String userId, String intent);
}
