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

}
