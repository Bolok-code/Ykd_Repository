package ykd.ykd.memory.mapper;

import org.apache.ibatis.annotations.*;
import ykd.ykd.memory.model.ConversationMessage;

import java.util.List;

@Mapper
public interface ConversationMessageMapper {
    @Insert("""
            INSERT INTO conversation_message (
                user_id,
                role,
                content,
                message_type,
                model_name,
                created_at
            )
            VALUES (
                #{userId},
                #{role},
                #{content},
                #{messageType},
                #{modelName},
                #{createdAt}
            )
            """)
    @SelectKey(
            statement = "SELECT last_insert_rowid()",
            keyProperty = "id",
            before = false,
            resultType = Long.class)
    int insert(ConversationMessage message);
    /**
     * 查询某个用户最近的消息，并恢复成从旧到新的顺序。
     */
    @Select("""
            SELECT
                id,
                user_id,
                role,
                content,
                message_type,
                model_name,
                created_at
            FROM (
                SELECT
                    id,
                    user_id,
                    role,
                    content,
                    message_type,
                    model_name,
                    created_at
                FROM conversation_message
                WHERE user_id = #{userId}
                ORDER BY id DESC
                LIMIT #{limit}
            ) recent_messages
            ORDER BY id ASC
            """)
    List<ConversationMessage> findRecentByUserId(
            @Param("userId") String userId,
            @Param("limit") int limit
    );

    /**
     * 清空某个用户的全部对话记录。
     */
    @Delete("""
            DELETE FROM conversation_message
            WHERE user_id = #{userId}
            """)
    int deleteByUserId(@Param("userId") String userId);

    /**
     * 删除指定用户在某个ID之前的所有消息
     */
    @Delete("""
            DELETE FROM conversation_message
            WHERE user_id = #{userId} AND id < #{maxId}
            """)
    int deleteByUserIdBeforeId(
            @Param("userId") String userId,
            @Param("maxId") Long maxId
    );

    /**
     * 查询指定用户的全部消息，按时间从旧到新排列。
     */
    @Select("""
            SELECT id, user_id, role, content, message_type, model_name, created_at
            FROM conversation_message
            WHERE user_id = #{userId}
            ORDER BY id ASC
            """)
    List<ConversationMessage> findAllByUserId(@Param("userId") String userId);

    /**
     * 删除早于指定天数的所有消息，用于全局清理防止表无限增长。
     * datetime() 兼容 Java 写入的 ISO-8601（含 T）和 SQLite DEFAULT 的 "YYYY-MM-DD HH:MM:SS"。
     */
    @Delete("""
            DELETE FROM conversation_message
            WHERE datetime(created_at) < datetime('now', #{modifier})
            """)
    int deleteOlderThan(@Param("modifier") String modifier);

}
