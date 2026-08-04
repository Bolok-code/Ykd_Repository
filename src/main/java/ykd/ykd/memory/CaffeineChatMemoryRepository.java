package ykd.ykd.memory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Caffeine 驱动的对话记忆存储，替代 {@code InMemoryChatMemoryRepository}。
 *
 * <p>沉默用户 30 分钟后自动从堆中淘汰，下次再说话时从 SQLite 重新恢复。
 * 500 个用户上限防止长时间运行后 ChatMemory 无限膨胀。</p>
 */
@Slf4j
public final class CaffeineChatMemoryRepository implements ChatMemoryRepository {

    private final Cache<String, List<Message>> cache;

    public CaffeineChatMemoryRepository() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .removalListener((String userId, List<Message> messages, RemovalCause cause) -> {
                    if (cause.wasEvicted()) {
                        log.info("[ChatMemory] 用户 {} 对话内存已淘汰: cause={}, messages={}",
                                userId, cause, messages != null ? messages.size() : 0);
                    }
                })
                .build();
    }

    @Override
    public List<String> findConversationIds() {
        return List.copyOf(cache.asMap().keySet());
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        List<Message> messages = cache.getIfPresent(conversationId);
        return messages != null ? messages : List.of();
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        cache.put(conversationId, List.copyOf(messages));
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        cache.invalidate(conversationId);
    }
}
