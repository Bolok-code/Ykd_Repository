package com.clitoolbox.conversation;

import com.clitoolbox.ai.ChatMessage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存会话仓库。按用户隔离，并限制每个用户保存的消息数量。
 */
public class MemoryConversationRepository implements ConversationRepository {
    private final ConcurrentMap<String, Deque<ChatMessage>> conversations = new ConcurrentHashMap<>();
    private final int maxMessagesPerUser;

    public MemoryConversationRepository(int maxRounds) {
        if (maxRounds < 1) {
            throw new IllegalArgumentException("maxRounds 必须大于 0");
        }
        this.maxMessagesPerUser = maxRounds * 2;
    }

    @Override
    public List<ChatMessage> findByUserId(String userId) {
        Deque<ChatMessage> messages = conversations.get(userId);
        if (messages == null) {
            return List.of();
        }
        synchronized (messages) {
            return List.copyOf(new ArrayList<>(messages));
        }
    }

    @Override
    public void appendTurn(String userId, ChatMessage userMessage, ChatMessage assistantMessage) {
        Deque<ChatMessage> messages = conversations.computeIfAbsent(
                userId,
                ignored -> new ArrayDeque<>());
        synchronized (messages) {
            messages.addLast(userMessage);
            messages.addLast(assistantMessage);
            while (messages.size() > maxMessagesPerUser) {
                messages.removeFirst();
            }
        }
    }

    @Override
    public void clear(String userId) {
        conversations.remove(userId);
    }
}
