package com.clitoolbox.conversation;

import com.clitoolbox.ai.ChatMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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

    @Override
    public Optional<ConversationContext> findLatestContext(String userId, String intent) {
        String sql = """
            SELECT intent, city, target_date
            FROM conversation_message
            WHERE user_id = ? AND intent = ?
            AND city IS NOT NULL
            AND city <> ''
            ORDER BY id DESC
            LIMIT 1
            """;
        List<ConversationContext> contexts = jdbcTemplate.query(
                sql,
                (resultSet,rowNum)->new ConversationContext(
                        resultSet.getString("intent"),
                        resultSet.getString("city"),
                        resultSet.getObject("target_date", LocalDate.class)
                ),
                userId,
                intent
        );
        return contexts.stream().findFirst();
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
