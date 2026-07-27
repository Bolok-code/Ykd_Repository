package ykd.ykd.memory.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ykd.ykd.memory.mapper.ConversationMessageMapper;
import ykd.ykd.memory.model.ConversationMessage;
import ykd.ykd.memory.service.ConversationHistoryService;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ConversationHistoryServiceImpl
        implements ConversationHistoryService {

    private static final int MAX_HISTORY_LIMIT = 40;

    private final ConversationMessageMapper conversationMessageMapper;

    @Override
    public List<ConversationMessage> findRecentMessages(
            String userId,
            int limit) {

        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId不能为空");
        }

        int safeLimit = Math.max(
                1,
                Math.min(limit, MAX_HISTORY_LIMIT)
        );

        return conversationMessageMapper.findRecentByUserId(
                userId,
                safeLimit
        );
    }

    @Override
    @Transactional
    public void saveTurn(
            String userId,
            String userContent,
            String assistantContent,
            String modelName) {

        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId不能为空");
        }

        if (userContent == null || userContent.isBlank()) {
            throw new IllegalArgumentException("用户消息不能为空");
        }

        if (assistantContent == null || assistantContent.isBlank()) {
            throw new IllegalArgumentException("机器人回答不能为空");
        }

        String createdAt = LocalDateTime.now().toString();

        ConversationMessage userMessage =
                new ConversationMessage(
                        null,
                        userId,
                        "user",
                        userContent,
                        "text",
                        null,
                        createdAt
                );

        ConversationMessage assistantMessage =
                new ConversationMessage(
                        null,
                        userId,
                        "assistant",
                        assistantContent,
                        "text",
                        modelName,
                        createdAt
                );

        conversationMessageMapper.insert(userMessage);
        conversationMessageMapper.insert(assistantMessage);
    }

    @Override
    public int clearHistory(String userId) {

        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId不能为空");
        }

        return conversationMessageMapper.deleteByUserId(userId);
    }
}