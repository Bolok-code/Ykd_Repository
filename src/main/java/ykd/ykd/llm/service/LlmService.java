package ykd.ykd.llm.service;

import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

public interface LlmService {

    /**
     * 发送聊天请求。
     *
     * @param text      用户文本，同时也是 Skill 路由的依据
     * @param imageUrls 图片 URL 列表
     * @param client    ChatClient 实例
     * @param userId    用户 ID
     */
    String chat(String text, List<String> imageUrls, ChatClient client, String userId);

    /**
     * 带上下文前缀的聊天请求。
     *
     * <p>{@code systemContext} 会作为系统消息前置注入，用于传递文档内容等上下文；
     * {@code text} 仅用于 Skill 路由和用户消息，不会混入上下文，避免误触发 Skill。</p>
     *
     * @param text          用户文本，Skill 路由依据
     * @param imageUrls     图片 URL 列表
     * @param client        ChatClient 实例
     * @param userId        用户 ID
     * @param systemContext 系统消息上下文（文档内容等）；为 {@code null} 时不注入
     */
    String chat(String text, List<String> imageUrls, ChatClient client, String userId, String systemContext);
}
