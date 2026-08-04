package ykd.ykd.memory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import ykd.ykd.memory.model.ConversationMessage;
import ykd.ykd.memory.service.ConversationHistoryService;
import ykd.ykd.memory.service.impl.ConversationHistoryServiceImpl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ConversationHistoryServiceImpl.class)
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        "spring.datasource.hikari.maximum-pool-size=1"
})
@Sql(scripts = "classpath:db/sqlite/schema.sql")
class ConversationHistoryServiceTest {

    private static final String USER_ONE = "test-user-one";
    private static final String USER_TWO = "test-user-two";

    /** 每次 JVM 运行使用唯一 DB 文件，避免并发跑测试时共用 conversation-test.db 冲突 */
    private static final Path TEST_DB = Paths.get("target", "conversation-test-" + UUID.randomUUID() + ".db");

    @DynamicPropertySource
    static void dbProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + TEST_DB);
    }

    @Autowired
    private ConversationHistoryService conversationHistoryService;

    @AfterEach
    void cleanUp() {
        conversationHistoryService.clearHistory(USER_ONE);
        conversationHistoryService.clearHistory(USER_TWO);
    }

    @AfterAll
    static void cleanUpDbFile() {
        try {
            Files.deleteIfExists(TEST_DB);
        } catch (IOException ignored) {
        }
    }

    @Test
    void shouldSaveAndReadOneConversationTurnInOrder() {
        conversationHistoryService.saveTurn(
                USER_ONE,
                "你好",
                "你好，有什么可以帮你？",
                "DeepSeek"
        );

        List<ConversationMessage> messages =
                conversationHistoryService.findRecentMessages(USER_ONE, 40);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getId()).isNotNull();
        assertThat(messages.get(0).getRole()).isEqualTo("user");
        assertThat(messages.get(0).getContent()).isEqualTo("你好");
        assertThat(messages.get(1).getRole()).isEqualTo("assistant");
        assertThat(messages.get(1).getContent()).isEqualTo("你好，有什么可以帮你？");
        assertThat(messages.get(1).getModelName()).isEqualTo("DeepSeek");
        assertThat(messages.get(0).getId()).isLessThan(messages.get(1).getId());
    }

    @Test
    void shouldKeepDifferentUsersIsolated() {
        conversationHistoryService.saveTurn(USER_ONE, "用户一", "回答一", "DeepSeek");
        conversationHistoryService.saveTurn(USER_TWO, "用户二", "回答二", "Agnes");

        List<ConversationMessage> userOneMessages =
                conversationHistoryService.findRecentMessages(USER_ONE, 40);
        List<ConversationMessage> userTwoMessages =
                conversationHistoryService.findRecentMessages(USER_TWO, 40);

        assertThat(userOneMessages)
                .extracting(ConversationMessage::getUserId)
                .containsOnly(USER_ONE);
        assertThat(userTwoMessages)
                .extracting(ConversationMessage::getUserId)
                .containsOnly(USER_TWO);
        assertThat(userOneMessages)
                .extracting(ConversationMessage::getContent)
                .containsExactly("用户一", "回答一");
        assertThat(userTwoMessages)
                .extracting(ConversationMessage::getContent)
                .containsExactly("用户二", "回答二");
    }
}
