package com.clitoolbox.conversation;

import com.clitoolbox.ai.ChatMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

public class JdbcConversationRepository implements ConversationRepository {
    private final JdbcTemplate jdbcTemplate;
    private final int maxMessagesPerUser;

    public JdbcConversationRepository(
            JdbcTemplate jdbcTemplate,
            int maxRounds) {
      if(jdbcTemplate == null){
          throw new IllegalArgumentException("jdbcTemplate 不能为空");
      }
      if (maxRounds<1){
          throw new IllegalArgumentException("maxRounds 必须大于0");
      }
      this.jdbcTemplate= jdbcTemplate;
      this.maxMessagesPerUser = maxRounds*2;
    }
//查询
    @Override
    public List<ChatMessage> findByUserId(String userId) {

        String sql = """
            SELECT role, content
            FROM (
                SELECT id, role, content
                FROM conversation_message
                WHERE user_id = ?
                ORDER BY id DESC
                LIMIT ?
            ) AS recent_messages
            ORDER BY id ASC
            """;

        return jdbcTemplate.query(
                sql,
                (resultSet, rowNum) -> new ChatMessage(
                        resultSet.getString("role"),
                        resultSet.getString("content")
                ),
                userId,
                maxMessagesPerUser
        );
    }
//新增逻辑
    @Override
    @Transactional
    public void appendTurn(String userId,
                           ConversationTurn turn) {
      insertMessage(userId, turn.userMessage(),turn);
      insertMessage(userId, turn.assistantMessage(),turn);

    }
    //删除逻辑
    @Override
    public void clear(String userId) {
        String sql = """
                DELETE FROM conversation_message
                WHERE user_id = ?
                """;
        jdbcTemplate.update(sql, userId);
    }
    private void insertMessage(String userId, ChatMessage message, ConversationTurn turn) {
        String sql = """
            INSERT INTO conversation_message
                (user_id, role, content,intent,city,target_date)
            VALUES (?, ?, ?,?,?,?)
            """;
        jdbcTemplate.update(
                sql,
                userId,
                message.role(),
                message.content(),
                turn.intent(),
                turn.city(),
                turn.targetDate()
        );
    }
}
