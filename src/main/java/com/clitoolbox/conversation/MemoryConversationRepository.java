package com.clitoolbox.conversation;

import com.clitoolbox.ai.ChatMessage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存会话仓库。按用户隔离，并限制每个用户保存的消息数量。
 */
public class MemoryConversationRepository implements ConversationRepository {
    private final ConcurrentMap<String, Deque<ChatMessage>> conversations = new ConcurrentHashMap<>();
    private final int maxMessagesPerUser;
    private final ConcurrentMap<String,
            ConcurrentMap<String,ConversationContext>> latestContexts = new ConcurrentHashMap<>();
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
    public Optional<ConversationContext> findLatestContext(
            String userId,
            String intent
    ){
        ConcurrentMap<String,ConversationContext> userContexts =
                latestContexts.get(userId);
        if (userContexts == null||intent==null||intent.isBlank()){
            return Optional.empty();
        }
        String normalizedIntent = intent.trim()
                .toUpperCase(Locale.ROOT);
        return  Optional.ofNullable(
                userContexts.get(normalizedIntent)
        );
    }
    @Override
    public void appendTurn(String userId, ConversationTurn turn) {
        Deque<ChatMessage> messages = conversations.computeIfAbsent(
                userId,
                ignored -> new ArrayDeque<>());
        synchronized (messages) {
            messages.addLast(turn.userMessage());
            messages.addLast(turn.assistantMessage());
            while (messages.size() > maxMessagesPerUser) {
                messages.removeFirst();
            }
        }
        ConversationContext context = new ConversationContext(
                turn.intent(),
                turn.city()
                ,turn.targetDate()
        );
        latestContexts
                .computeIfAbsent(
                        userId,
                        ignored->new ConcurrentHashMap<>()
                ).put(turn.intent(),context);
    }

    @Override
    public void clear(String userId) {
        conversations.remove(userId);
        latestContexts.remove(userId);
    }
}
