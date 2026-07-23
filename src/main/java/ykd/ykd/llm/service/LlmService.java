package ykd.ykd.llm.service;

import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

public interface LlmService {
    String chat(String text, List<String> imageUrls, ChatClient client, String userId);
}
