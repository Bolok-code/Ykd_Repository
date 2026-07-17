package ykd.ykd.llm.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import ykd.ykd.llm.service.LlmService;
@Service
@RequiredArgsConstructor
public class LlmServiceImpl implements LlmService {
    private final ChatClient chatClient;

    @Override
    public String chat(String content) {
        String system = "You are a helpful assistant.";
        String usercontent = "正文如下：\n\n" + content + "\n\n请直接给出不超过200字的回复。";
        return chatClient.prompt()
                .system(system)
                .user(usercontent)
                .call()
                .content();
    }
}
