package ykd.ykd.memory;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Repository
public class JdbcChatMemoryRepository implements ChatMemoryRepository {

    private final ChatHistoryMapper mapper;

    public JdbcChatMemoryRepository(ChatHistoryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findConversationIds() {
        List<String> ids = mapper.findConversationIds();
        return ids != null ? ids : Collections.emptyList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Message> findByConversationId(String conversationId) {
        List<ChatMessage> rows = mapper.findByConversationId(conversationId);
        if (rows == null) {
            return Collections.emptyList();
        }
        List<Message> messages = new ArrayList<>(rows.size());
        for (ChatMessage row : rows) {
            messages.add(buildMessage(row.getMessageType(), row.getTextContent()));
        }
        return messages;
    }

    @Override
    @Transactional
    public void saveAll(String conversationId, List<Message> messages) {
        for (Message msg : messages) {
            mapper.insert(conversationId, msg.getMessageType().getValue(), msg.getText());
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        mapper.deleteByConversationId(conversationId);
    }

    private Message buildMessage(String type, String text) {
        if (type == null) {
            return new UserMessage(text != null ? text : "");
        }
        MessageType messageType = MessageType.valueOf(type.toUpperCase());
        return switch (messageType) {
            case ASSISTANT -> new AssistantMessage(text != null ? text : "");
            case SYSTEM -> new AssistantMessage(text != null ? text : "");
            default -> new UserMessage(text != null ? text : "");
        };
    }
}
