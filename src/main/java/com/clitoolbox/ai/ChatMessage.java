package com.clitoolbox.ai;

import java.util.Set;

/**
 * Chat Completions 标准消息。
 */
public record ChatMessage(String role, String content) {
    private static final Set<String> ALLOWED_ROLES = Set.of("system", "user", "assistant");

    public ChatMessage {
        if (role == null || !ALLOWED_ROLES.contains(role)) {
            throw new IllegalArgumentException("不支持的消息角色: " + role);
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }
}
