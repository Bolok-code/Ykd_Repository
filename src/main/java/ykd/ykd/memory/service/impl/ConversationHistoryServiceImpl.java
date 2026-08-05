package ykd.ykd.memory.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ykd.ykd.memory.mapper.ConversationMessageMapper;
import ykd.ykd.memory.model.ConversationMessage;
import ykd.ykd.memory.service.ConversationHistoryService;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationHistoryServiceImpl
        implements ConversationHistoryService {

    private final ConversationMessageMapper conversationMessageMapper;

    /**
     * 查询用户最近的对话消息。
     *
     * <p>若传入的 {@code limit} 小于 1，自动修正为 1，防止查询空结果。</p>
     *
     * @param userId 用户 ID，不能为空
     * @param limit  返回的最大消息条数，最小值为 1
     * @return 按时间倒序排列的最近对话消息列表
     * @throws IllegalArgumentException 当 {@code userId} 为 {@code null} 或空字符串时
     */
    @Override
    public List<ConversationMessage> findRecentMessages(
            String userId,
            int limit) {

        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId不能为空");
        }

        int safeLimit = Math.max(1, limit);

        return conversationMessageMapper.findRecentByUserId(
                userId,
                safeLimit
        );
    }

    /**
     * 保存一轮对话（用户消息 + 助手回复）。
     *
     * <p>将用户消息和助手回复分别构建为 {@link ConversationMessage}，
     * 使用相同的创建时间批量写入数据库，保证同一轮对话的时间一致性。</p>
     *
     * @param userId           用户 ID，不能为空
     * @param userContent      用户消息内容，不能为空
     * @param assistantContent 助手回复内容，不能为空
     * @param modelName        使用的 AI 模型名称，仅写入助手消息中
     * @throws IllegalArgumentException 当任一必填参数为 {@code null} 或空字符串时
     */
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

        ConversationMessage userMessage = ConversationMessage.builder()
                .userId(userId)
                .role("user")
                .content(userContent)
                .messageType("text")
                .createdAt(createdAt)
                .build();

        ConversationMessage assistantMessage = ConversationMessage.builder()
                .userId(userId)
                .role("assistant")
                .content(assistantContent)
                .messageType("text")
                .modelName(modelName)
                .createdAt(createdAt)
                .build();

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

    @Override
    public List<ConversationMessage> findAllMessages(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId不能为空");
        }
        return conversationMessageMapper.findAllByUserId(userId);
    }

    /**
     * 替换用户对话历史：清空旧记录后写入摘要与近期消息。
     *
     * <p>用于 Token 压缩场景——先删除该用户全部历史，再将 AI 生成的对话摘要作为
     * {@code assistant} 角色写入，最后逐条复制近期消息，所有写入使用同一时间戳。</p>
     *
     * @param userId         用户 ID，不能为空
     * @param summaryContent AI 生成的对话摘要内容
     * @param recentMessages 需要保留的近期消息列表，逐条复制后写入
     * @throws IllegalArgumentException 当 {@code userId} 为 {@code null} 或空字符串时
     */
    @Override
    @Transactional
    public void replaceHistory(String userId,
                               String summaryContent,
                               List<ConversationMessage> recentMessages) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId不能为空");
        }

        conversationMessageMapper.deleteByUserId(userId);

        String createdAt = java.time.LocalDateTime.now().toString();

        ConversationMessage summaryMessage = ConversationMessage.builder()
                .userId(userId)
                .role("assistant")
                .content(summaryContent)
                .messageType("text")
                .createdAt(createdAt)
                .build();
        conversationMessageMapper.insert(summaryMessage);

        for (ConversationMessage message : recentMessages) {
            ConversationMessage copy = ConversationMessage.builder()
                    .userId(userId)
                    .role(message.getRole())
                    .content(message.getContent())
                    .messageType(message.getMessageType())
                    .modelName(message.getModelName())
                    .createdAt(createdAt)
                    .build();
            conversationMessageMapper.insert(copy);
        }
    }

    @Override
    public int cleanupOldMessages(int retentionDays) {
        if (retentionDays < 1) {
            return 0;
        }
        String cutoff = LocalDateTime.now().minusDays(retentionDays).toString();
        int deleted = conversationMessageMapper.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("[Conversation] 清理过期历史消息: retentionDays={}, cutoff={}, deleted={}",
                    retentionDays, cutoff, deleted);
        }
        return deleted;
    }
}
