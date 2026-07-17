package com.clitoolbox.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.clitoolbox.ai.AiChatClient;
import com.clitoolbox.ai.ChatMessage;
import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatServiceTest {

    @Test
    void includesPreviousConversationForSameUser() {
        RecordingAiClient ai = new RecordingAiClient();
        ChatService service = new ChatService(
                ai,
                new MemoryConversationRepository(10),
                "系统提示");

        assertEquals("回答1", service.chat("user-a", "我叫小明"));
        assertEquals("回答2", service.chat("user-a", "我叫什么？"));

        List<ChatMessage> secondRequest = ai.requests.get(1);
        assertEquals(4, secondRequest.size());
        assertEquals(ChatMessage.system("系统提示"), secondRequest.get(0));
        assertEquals(ChatMessage.user("我叫小明"), secondRequest.get(1));
        assertEquals(ChatMessage.assistant("回答1"), secondRequest.get(2));
        assertEquals(ChatMessage.user("我叫什么？"), secondRequest.get(3));
    }

    @Test
    void isolatesConversationBetweenUsers() {
        RecordingAiClient ai = new RecordingAiClient();
        ChatService service = new ChatService(
                ai,
                new MemoryConversationRepository(10),
                "系统提示");

        service.chat("user-a", "A的秘密");
        service.chat("user-b", "B的问题");

        List<ChatMessage> userBRequest = ai.requests.get(1);
        assertEquals(2, userBRequest.size());
        assertEquals(ChatMessage.user("B的问题"), userBRequest.get(1));
    }

    @Test
    void clearCommandRemovesHistoryWithoutCallingModel() {
        RecordingAiClient ai = new RecordingAiClient();
        ChatService service = new ChatService(
                ai,
                new MemoryConversationRepository(10),
                "系统提示");

        service.chat("user-a", "记住这句话");
        assertEquals("当前对话记录已清空，我们重新开始吧。", service.chat("user-a", "/clear"));
        assertEquals(1, ai.requests.size());

        service.chat("user-a", "还有历史吗？");
        assertEquals(2, ai.requests.get(1).size());
    }

    @Test
    void doesNotSaveFailedRequestIntoHistory() {
        AiChatClient failing = new AiChatClient() {
            @Override
            public String chat(List<ChatMessage> messages) {
                throw new CliException(ErrorCode.NETWORK_ERROR, "模拟失败");
            }

            @Override
            public String modelName() {
                return "test-model";
            }
        };
        MemoryConversationRepository repository = new MemoryConversationRepository(10);
        ChatService service = new ChatService(failing, repository, "系统提示");

        assertThrows(CliException.class, () -> service.chat("user-a", "失败消息"));
        assertEquals(List.of(), repository.findByUserId("user-a"));
    }

    @Test
    void rejectsOversizedUserMessageBeforeCallingModel() {
        RecordingAiClient ai = new RecordingAiClient();
        ChatService service = new ChatService(
                ai,
                new MemoryConversationRepository(10),
                "系统提示");

        CliException error = assertThrows(
                CliException.class,
                () -> service.chat("user-a", "字".repeat(ChatService.MAX_INPUT_CHARS + 1)));

        assertEquals(ErrorCode.INVALID_INPUT, error.getErrorCode());
        assertEquals(0, ai.requests.size());
    }

    private static final class RecordingAiClient implements AiChatClient {
        private final List<List<ChatMessage>> requests = new ArrayList<>();

        @Override
        public String chat(List<ChatMessage> messages) {
            requests.add(List.copyOf(messages));
            return "回答" + requests.size();
        }

        @Override
        public String modelName() {
            return "test-model";
        }
    }
}
