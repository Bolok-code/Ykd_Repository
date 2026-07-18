package ykd.ykd.llm.service;

import org.springframework.ai.chat.client.ChatClient;

public interface LlmService {
    String chat(String text, String imageUrl, ChatClient client);
}
