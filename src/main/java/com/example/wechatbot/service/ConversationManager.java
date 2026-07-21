package com.example.wechatbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ConversationManager {
    private static final Logger log = LoggerFactory.getLogger(ConversationManager.class);
    private final ConcurrentHashMap<String, LinkedList<Map<String, String>>> sessions = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY = 20;

    public synchronized void addMessage(String userId, String role, String content) {
        LinkedList<Map<String, String>> history = sessions.computeIfAbsent(userId, k -> new LinkedList<>());
        history.addLast(Map.of("role", role, "content", content));
        if (history.size() > MAX_HISTORY) history.removeFirst();
    }

    public synchronized List<Map<String, String>> getContext(String userId) {
        LinkedList<Map<String, String>> history = sessions.get(userId);
        if (history == null) return List.of();
        return List.copyOf(history);
    }

    public synchronized void clearContext(String userId) {
        sessions.remove(userId);
        log.info("已清除用户 {} 的对话历史", userId);
    }
}
