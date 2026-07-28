package ykd.ykd.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import ykd.ykd.memory.model.ConversationMessage;
import ykd.ykd.memory.service.ConversationHistoryService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryManagerServiceTest {

    private static final String USER_ID = "memory-test-user";

    @Test
    void shouldRestoreDatabaseHistoryOnlyOnce() {
        ConversationHistoryService historyService = mock(ConversationHistoryService.class);
        when(historyService.findAllMessages(USER_ID)).thenReturn(List.of(
                message(1L, "user", "我叫小明", null),
                message(2L, "assistant", "你好，小明", "DeepSeek")
        ));

        MemoryManagerService manager = new MemoryManagerService(
                newChatMemory(),
                mock(ChatClient.class),
                historyService
        );

        List<Message> firstRead = manager.getHistory(USER_ID);
        List<Message> secondRead = manager.getHistory(USER_ID);

        assertThat(firstRead)
                .extracting(Message::getText)
                .containsExactly("我叫小明", "你好，小明");
        assertThat(secondRead)
                .extracting(Message::getText)
                .containsExactly("我叫小明", "你好，小明");

        verify(historyService, times(1))
                .findAllMessages(USER_ID);
    }

    @Test
    void shouldPersistAndCacheNewConversationTurn() {
        ConversationHistoryService historyService = mock(ConversationHistoryService.class);
        when(historyService.findAllMessages(USER_ID)).thenReturn(List.of());

        MemoryManagerService manager = new MemoryManagerService(
                newChatMemory(),
                mock(ChatClient.class),
                historyService
        );

        manager.save(USER_ID, "今天天气怎么样", "今天晴朗", "DeepSeek");

        verify(historyService).saveTurn(
                USER_ID,
                "今天天气怎么样",
                "今天晴朗",
                "DeepSeek"
        );
        assertThat(manager.getHistory(USER_ID))
                .extracting(Message::getText)
                .containsExactly("今天天气怎么样", "今天晴朗");
    }

    private ChatMemory newChatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(Integer.MAX_VALUE)
                .build();
    }

    private ConversationMessage message(Long id,
                                        String role,
                                        String content,
                                        String modelName) {
        return new ConversationMessage(
                id,
                USER_ID,
                role,
                content,
                "text",
                modelName,
                "2026-07-27T12:00:00"
        );
    }
}