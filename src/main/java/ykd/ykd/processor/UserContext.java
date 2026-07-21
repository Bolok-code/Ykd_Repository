package ykd.ykd.processor;

import org.springframework.stereotype.Component;

/**
 * 用户上下文，通过 ThreadLocal 在当前线程传递当前处理消息的 userId。
 *
 * <p>供 {@link MessageProcessor} 设置，{@code @Tool} 方法读取。
 * 依赖 Spring AI {@code @Tool} 回调与 {@code ChatClient.call()} 在同一线程执行。</p>
 */
@Component
public class UserContext {

    private final ThreadLocal<String> currentUserId = new ThreadLocal<>();

    /**
     * 在指定用户上下文中执行操作，执行后自动清理。
     */
    public void executeAs(String userId, Runnable action) {
        try {
            currentUserId.set(userId);
            action.run();
        } finally {
            currentUserId.remove();
        }
    }

    public String getCurrentUserId() {
        return currentUserId.get();
    }
}
