package ykd.ykd.memory.service;

import ykd.ykd.memory.model.ConversationMessage;

import java.util.List;

public interface ConversationHistoryService {
    /**
     * 按时间顺序查询指定用户最近的消息。
     *
     * @param userId 用户标识
     * @param limit 最大消息数量
     * @return 从旧到新排列的消息列表
     */
    List<ConversationMessage> findRecentMessages(String userId, int limit);

    /**
     * 保存一轮完整对话：一条用户消息和一条机器人消息。
     */
    void saveTurn(String userId,
                  String userContent,
                  String assistantContent,
                  String modelName);

    /**
     * 清空指定用户的对话记录。
     */
    int clearHistory(String userId);

    /**
     * 查询指定用户的全部消息，按时间从旧到新排列。
     */
    List<ConversationMessage> findAllMessages(String userId);

    /**
     * 替换用户的历史记录：删除旧消息，存入新的摘要和最近消息。
     *
     * @param userId 用户标识
     * @param summaryContent 摘要内容
     * @param recentMessages 要保留的最近消息
     */
    void replaceHistory(String userId,
                        String summaryContent,
                        List<ConversationMessage> recentMessages);
}
