package ykd.ykd.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
    private final ChatClient summaryClient;

    public MemoryManagerService(ChatMemory chatMemory, ChatClient summaryClient) {
        this.chatMemory = chatMemory;
        this.summaryClient = summaryClient;
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

    /**
     * 历史消息超过阈值时将老消息压缩为摘要，保留最近对话原文。
     *
     * <p>需在 per-user 串行上下文中调用以保证 {@code clear() + add()} 原子性。</p>
     */
    public void compressIfNeeded(String userId) {
        List<Message> history = chatMemory.get(userId);
        if (history == null || history.size() < 30) {
            return;
        }

        List<Message> toCompress = new ArrayList<>(history.subList(0, 20));
        List<Message> recent = new ArrayList<>(history.subList(20, history.size()));

        log.info("[MemoryManager] 开始压缩: userId={}, 压缩{}条→摘要, 保留{}条",
                userId, toCompress.size(), recent.size());

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

        log.info("[MemoryManager] 压缩完成: userId={}, 摘要长度={}, 总计{}条",
                userId, summary.length(), replacement.size());
    }
}
