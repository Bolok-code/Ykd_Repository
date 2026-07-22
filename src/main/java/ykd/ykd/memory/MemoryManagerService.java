package ykd.ykd.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 对话记忆管理器，封装 {@link ChatMemory}，按 userId 隔离。
 *
 * <p>当前用 {@code InMemoryChatMemoryRepository} 存储，重启丢失。
 * 换 JDBC 实现时无需修改此类。</p>
 */
@Slf4j
@Component
public class MemoryManagerService {

    private final ChatMemory chatMemory;

    public MemoryManagerService(ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
    }

    /**
     * 获取用户对话历史。
     */
    public List<Message> getHistory(String userId) {
        List<Message> history = chatMemory.get(userId);
        return history != null ? history : Collections.emptyList();
    }

    /**
     * 保存一轮对话。
     */
    public void save(String userId, String userText, String assistantReply) {
        chatMemory.add(userId, List.of(
                new UserMessage(userText),
                new AssistantMessage(assistantReply)
        ));
        log.debug("[MemoryManager] 保存对话: userId={}", userId);
    }

    /**
     * 清除用户对话记忆。
     */
    public void clear(String userId) {
        chatMemory.clear(userId);
        log.info("[MemoryManager] 清除记忆: userId={}", userId);
    }
}
