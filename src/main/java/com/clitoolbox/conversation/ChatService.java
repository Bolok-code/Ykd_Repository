package com.clitoolbox.conversation;

import com.clitoolbox.ai.AiChatClient;
import com.clitoolbox.ai.ChatMessage;
import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 多轮聊天业务服务。
 */
public class ChatService {
    static final int MAX_INPUT_CHARS = 4_000;
    static final int MAX_HISTORY_CHARS = 20_000;
    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是一个通过微信与用户交流的中文智能助手。回答应准确、友好、简洁；"
            + "不知道的信息要明确说明，不要编造实时数据。";

    private final AiChatClient aiChatClient;
    private final ConversationRepository repository;
    private final String systemPrompt;

    public ChatService(AiChatClient aiChatClient, ConversationRepository repository) {
        this(aiChatClient, repository, loadSystemPrompt());
    }

    ChatService(
            AiChatClient aiChatClient,
            ConversationRepository repository,
            String systemPrompt) {
        this.aiChatClient = aiChatClient;
        this.repository = repository;
        this.systemPrompt = systemPrompt;
    }

    public String chat(String userId, String text) {
        if (userId == null || userId.isBlank()) {
            throw new CliException(ErrorCode.INVALID_INPUT, "微信用户 ID 不能为空。");
        }
        if (text == null || text.isBlank()) {
            throw new CliException(ErrorCode.INVALID_INPUT, "聊天消息不能为空。");
        }

        String trimmed = text.trim();
        if (trimmed.length() > MAX_INPUT_CHARS) {
            throw new CliException(
                    ErrorCode.INVALID_INPUT,
                    "单条消息过长，请控制在 " + MAX_INPUT_CHARS + " 个字符以内。");
        }
        String commandResult = handleCommand(userId, trimmed);
        if (commandResult != null) {
            return commandResult;
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        messages.addAll(historyWithinCharacterBudget(repository.findByUserId(userId)));
        ChatMessage userMessage = ChatMessage.user(trimmed);
        messages.add(userMessage);

        String answer = aiChatClient.chat(messages);
        repository.append(userId, userMessage);
        repository.append(userId, ChatMessage.assistant(answer));
        return answer;
    }

    private List<ChatMessage> historyWithinCharacterBudget(List<ChatMessage> history) {
        if (history.isEmpty()) {
            return history;
        }
        List<ChatMessage> selectedReversed = new ArrayList<>();
        int usedCharacters = 0;

        // 历史按 user/assistant 成对保存，从最近一轮向前选取完整轮次。
        for (int index = history.size() - 2; index >= 0; index -= 2) {
            ChatMessage user = history.get(index);
            ChatMessage assistant = history.get(index + 1);
            int pairCharacters = user.content().length() + assistant.content().length();
            if (usedCharacters + pairCharacters > MAX_HISTORY_CHARS) {
                break;
            }
            selectedReversed.add(assistant);
            selectedReversed.add(user);
            usedCharacters += pairCharacters;
        }

        List<ChatMessage> selected = new ArrayList<>(selectedReversed.size());
        for (int index = selectedReversed.size() - 1; index >= 0; index--) {
            selected.add(selectedReversed.get(index));
        }
        return selected;
    }

    private String handleCommand(String userId, String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "/clear", "/new" -> {
                repository.clear(userId);
                yield "当前对话记录已清空，我们重新开始吧。";
            }
            case "/status" -> "AI 服务运行中，当前模型：" + aiChatClient.modelName();
            case "/model" -> "当前模型：" + aiChatClient.modelName();
            case "/help" -> """
                    可用命令：
                    /clear 或 /new - 清空当前对话
                    /status - 查看 AI 服务状态
                    /model - 查看当前模型
                    /help - 查看帮助
                    发送“北京天气”可查询实时天气。
                    直接发送图片可识别内容或解答图片中的题目。
                    发送“帮我生成一张……”可生成图片。""";
            default -> null;
        };
    }

    private static String loadSystemPrompt() {
        try (InputStream input = ChatService.class.getResourceAsStream("/prompts/system-prompt.txt")) {
            if (input == null) {
                return DEFAULT_SYSTEM_PROMPT;
            }
            String prompt = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            return prompt.isEmpty() ? DEFAULT_SYSTEM_PROMPT : prompt;
        } catch (IOException e) {
            return DEFAULT_SYSTEM_PROMPT;
        }
    }
}
