package ykd.ykd.memory;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ChatHistoryMapper {

    @Insert("INSERT INTO chat_history (conversation_id, message_type, text_content) VALUES (#{conversationId}, #{messageType}, #{textContent})")
    void insert(@Param("conversationId") String conversationId,
                @Param("messageType") String messageType,
                @Param("textContent") String textContent);

    @Select("SELECT message_type, text_content FROM chat_history WHERE conversation_id = #{conversationId} ORDER BY id")
    @Results({
        @Result(property = "messageType", column = "message_type"),
        @Result(property = "textContent", column = "text_content")
    })
    List<ChatMessage> findByConversationId(@Param("conversationId") String conversationId);

    @Delete("DELETE FROM chat_history WHERE conversation_id = #{conversationId}")
    void deleteByConversationId(@Param("conversationId") String conversationId);

    @Select("SELECT DISTINCT conversation_id FROM chat_history")
    List<String> findConversationIds();
}
