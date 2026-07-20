package com.clitoolbox.conversation;

import com.clitoolbox.ai.ChatMessage;

import java.time.LocalDate;
import java.util.Locale;

public record ConversationTurn(
        ChatMessage userMessage,
        ChatMessage assistantMessage,
        String intent,
        String city,
        LocalDate targetDate){
    public ConversationTurn{
        if (userMessage==null){
            throw new IllegalArgumentException("用户消息不能为空");
        }
        if(!"user".equals(userMessage.role()))
            throw new IllegalArgumentException("用户消息角色必须是user");
        if (assistantMessage==null){
            throw new IllegalArgumentException("助手消息不能为空");
        }
        if(!"assistant".equals(assistantMessage.role())){
            throw new IllegalArgumentException("assistantMessagr的角色必须是assistant");
        }
        if (intent==null||intent.isBlank()){
            throw new IllegalArgumentException("对话意图不能为空");
        }
        intent=intent.trim()
                .toUpperCase(Locale.ROOT);
        city=normalizeNullable(city);
    }


    private static String normalizeNullable(String value) {
        return value ==null ||value.isBlank()
                ?null:value.trim();
    }
    public static ConversationTurn textChat(
            ChatMessage userMessage,
            ChatMessage assistantMessage){
        return new ConversationTurn(
                userMessage,
                assistantMessage,
                "TEXT_CHAT",
                null,
                null);
    }
}
