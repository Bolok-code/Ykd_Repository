package com.clitoolbox.ai;

import com.clitoolbox.config.DeepSeekConfig;
import com.clitoolbox.config.DeepSeekProperties;
import com.clitoolbox.conversation.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * DeepSeek、会话仓库和微信聊天队列的 Spring Bean 配置。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeepSeekProperties.class)
public class DeepSeekAiConfiguration {

    @Bean
    @Lazy
    DeepSeekConfig deepSeekConfig(DeepSeekProperties properties) {
        return properties.toConfig();
    }

    @Bean
    @Lazy
    ChatModel deepSeekChatModel(
            DeepSeekConfig config,
            RestClient.Builder restClientBuilder) {
        return SpringAiDeepSeekClient.createChatModel(config, restClientBuilder);
    }

    @Bean
    @Lazy
    AiChatClient aiChatClient(
            DeepSeekConfig config,
            @Qualifier("deepSeekChatModel") ChatModel deepSeekChatModel) {
        return new SpringAiDeepSeekClient(config, deepSeekChatModel);
    }

    @Bean
    @Lazy
    ConversationRepository conversationRepository(
            DeepSeekConfig config,
            JdbcTemplate jdbcTemplate

    ) {
        return new JdbcConversationRepository(
                jdbcTemplate,
                config.historyRounds());
    }

    @Bean
    @Lazy
    ChatService chatService(
            AiChatClient aiChatClient,
            ConversationRepository conversationRepository) {
        return new ChatService(aiChatClient, conversationRepository);
    }

    @Bean(destroyMethod = "close")
    @Lazy
    PerUserTaskDispatcher perUserTaskDispatcher() {
        return new PerUserTaskDispatcher(8, 100, 5);
    }
}
