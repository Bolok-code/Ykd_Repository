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
}
