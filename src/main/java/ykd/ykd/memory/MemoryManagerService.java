package ykd.ykd.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import ykd.ykd.memory.model.ConversationMessage;
import ykd.ykd.memory.service.ConversationHistoryService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话记忆管理器。
 *
 * <p>SQLite 保存完整对话，Spring AI ChatMemory 保存当前运行时上下文。
 * 用户首次发言时，会从 SQLite 恢复最近的消息到 ChatMemory。</p>
 */
@Slf4j
@Component
public class MemoryManagerService {

    private static final int MAX_RESTORED_MESSAGES = 40;
    private static final int COMPRESS_THRESHOLD_TOKENS = 6000;
    private static final int KEEP_RECENT_TOKENS = 2500;

    private final ChatMemory chatMemory;
    private final ChatClient summaryClient;
    private final ConversationHistoryService conversationHistoryService;
    private final Set<String> hydratedUsers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Object> hydrationLocks = new ConcurrentHashMap<>();

    public MemoryManagerService(ChatMemory chatMemory,
                                ChatClient summaryClient,
                                ConversationHistoryService conversationHistoryService) {
        this.chatMemory = chatMemory;
        this.summaryClient = summaryClient;
        this.conversationHistoryService = conversationHistoryService;
    }

    /**
     * 获取用户对话历史。进程内第一次访问该用户时，先从 SQLite 恢复最近记录。
     */
    public List<Message> getHistory(String userId) {
        validateUserId(userId);
        hydrateFromDatabaseIfNeeded(userId);
        List<Message> history = chatMemory.get(userId);
        return history != null ? history : Collections.emptyList();
    }

    /**
     * 将一轮对话同时写入 SQLite 和运行时 ChatMemory。
     */
    public void save(String userId,
                     String userText,
                     String assistantReply,
                     String modelName) {
        validateUserId(userId);
        hydrateFromDatabaseIfNeeded(userId);

        conversationHistoryService.saveTurn(
                userId,
                userText,
                assistantReply,
                modelName
        );

        chatMemory.add(userId, List.of(
                new UserMessage(userText),
                new AssistantMessage(assistantReply)
        ));
        log.debug("[MemoryManager] 对话已写入 SQLite 和内存: userId={}, model={}",
                userId, modelName);
    }

    /**
     * 同时清除 SQLite 持久记录和当前进程中的上下文。
     */
    public void clear(String userId) {
        validateUserId(userId);
        int deleted = conversationHistoryService.clearHistory(userId);
        chatMemory.clear(userId);
        hydratedUsers.add(userId);
        log.info("[MemoryManager] 清除记忆: userId={}, deleted={}", userId, deleted);
    }

    /**
     * 只压缩当前运行时上下文，SQLite 中的原始消息不会被删除。
     */
    public void compressIfNeeded(String userId, int promptTokens) {
        validateUserId(userId);
        hydrateFromDatabaseIfNeeded(userId);

        List<Message> history = chatMemory.get(userId);
        if (history == null || history.isEmpty()) {
            return;
        }

        int actualTokens = promptTokens > 0 ? promptTokens : estimateTokens(history);
        if (actualTokens < COMPRESS_THRESHOLD_TOKENS) {
            return;
        }

        int keepTokens = 0;
        int splitIndex = history.size();
        for (int i = history.size() - 1; i >= 0; i--) {
            keepTokens += estimateTokens(history.get(i));
            if (keepTokens >= KEEP_RECENT_TOKENS) {
                splitIndex = i;
                break;
            }
        }
        if (splitIndex <= 0) {
            return;
        }

        List<Message> toCompress = new ArrayList<>(history.subList(0, splitIndex));
        List<Message> recent = new ArrayList<>(history.subList(splitIndex, history.size()));

        log.info("[MemoryManager] 开始压缩: userId={}, 总token≈{}, 压缩{}条→摘要, 保留{}条",
                userId, estimateTokens(history), toCompress.size(), recent.size());

        String summary;
        try {
            summary = summaryClient.prompt()
                    .messages(toCompress)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("[MemoryManager] 摘要生成失败: userId={}", userId, e);
            return;
        }

        if (summary == null || summary.isBlank()) {
            log.warn("[MemoryManager] 摘要为空，跳过压缩: userId={}", userId);
            return;
        }

        chatMemory.clear(userId);
        List<Message> replacement = new ArrayList<>();
        replacement.add(new AssistantMessage("[对话摘要] " + summary));
        replacement.addAll(recent);
        chatMemory.add(userId, replacement);

        log.info("[MemoryManager] 压缩完成: userId={}, 摘要长度={}, 保留{}条≈{}token",
                userId, summary.length(), recent.size(), estimateTokens(recent));
    }

    private void hydrateFromDatabaseIfNeeded(String userId) {
        if (hydratedUsers.contains(userId)) {
            return;
        }

        Object lock = hydrationLocks.computeIfAbsent(userId, ignored -> new Object());
        synchronized (lock) {
            if (hydratedUsers.contains(userId)) {
                return;
            }

            try {
                List<Message> restoredMessages = conversationHistoryService
                        .findRecentMessages(userId, MAX_RESTORED_MESSAGES)
                        .stream()
                        .map(this::toSpringAiMessage)
                        .filter(message -> message != null)
                        .toList();

                if (!restoredMessages.isEmpty()) {
                    chatMemory.add(userId, restoredMessages);
                    log.info("[MemoryManager] 从 SQLite 恢复历史: userId={}, count={}",
                            userId, restoredMessages.size());
                }
                hydratedUsers.add(userId);
            } finally {
                hydrationLocks.remove(userId, lock);
            }
        }
    }

    private Message toSpringAiMessage(ConversationMessage message) {
        if (message == null || message.getContent() == null) {
            return null;
        }

        return switch (message.getRole()) {
            case "user" -> new UserMessage(message.getContent());
            case "assistant" -> new AssistantMessage(message.getContent());
            case "system" -> new SystemMessage(message.getContent());
            default -> null;
        };
    }

    private void validateUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId不能为空");
        }
    }

    private int estimateTokens(List<Message> messages) {
        int chars = 0;
        for (Message message : messages) {
            String text = message.getText();
            if (text != null) {
                chars += text.length();
            }
        }
        return chars / 3;
    }

    private int estimateTokens(Message message) {
        String text = message.getText();
        return text != null ? text.length() / 3 : 0;
    }
}
