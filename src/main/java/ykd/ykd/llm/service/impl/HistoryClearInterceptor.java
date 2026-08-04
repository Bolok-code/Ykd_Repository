package ykd.ykd.llm.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.ykd.memory.MemoryManagerService;

import java.util.regex.Pattern;

/**
 * 历史记忆清除拦截器。
 *
 * <p>正则匹配用户消息中的清除意图，直接调用 {@link MemoryManagerService#clear}
 * 而不经过 LLM。此操作不可逆，仅匹配明确的清除指令。</p>
 */
@Slf4j
@Component
public class HistoryClearInterceptor {

    private final MemoryManagerService memoryManagerService;

    private static final Pattern CLEAR_PATTERN = Pattern.compile(
            "^(开启新对话|清除历史记忆|清除对话记录|清空聊天记录|重置对话)$"
    );

    public HistoryClearInterceptor(MemoryManagerService memoryManagerService) {
        this.memoryManagerService = memoryManagerService;
    }

    /**
     * 尝试拦截清除历史请求。
     *
     * @param text   用户消息文本
     * @param userId 微信用户 ID
     * @return 拦截成功返回确认消息，否则返回 null
     */
    public String tryIntercept(String text, String userId) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (!CLEAR_PATTERN.matcher(trimmed).matches()) return null;

        log.info("[HistoryClear] 用户触发清除历史: userId={}, text={}", userId, trimmed);
        memoryManagerService.clear(userId);
        return "已清除历史记忆，开启新对话。";
    }
}
