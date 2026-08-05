package ykd.ykd.memory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ykd.ykd.memory.service.ConversationHistoryService;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 对话历史清理任务。
 *
 * <p>conversation_message 表随对话持续增长，即使单用户有滑动窗口，
 * 长期运行仍会占用磁盘。本任务每天清理一次早于 {@code conversation.retention-days} 天的历史消息。</p>
 */
@Slf4j
@Component
public class ConversationCleanupJob {

    /** 消息保留天数，可在 application.yml 通过 conversation.retention-days 覆盖 */
    @Value("${conversation.retention-days:90}")
    private int retentionDays;

    /** 首次执行延迟：应用启动 1 小时后（避开启动繁忙期） */
    private static final long INITIAL_DELAY_MS = 60 * 60 * 1000L;
    private static final long PERIOD_MS = 24 * 60 * 60 * 1000L;

    private final ConversationHistoryService conversationHistoryService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "conversation-cleanup");
        t.setDaemon(true);
        return t;
    });

    public ConversationCleanupJob(ConversationHistoryService conversationHistoryService) {
        this.conversationHistoryService = conversationHistoryService;
    }

    @PostConstruct
    public void start() {
        scheduler.scheduleWithFixedDelay(this::cleanup, INITIAL_DELAY_MS, PERIOD_MS, TimeUnit.MILLISECONDS);
        log.info("[ConversationCleanup] 清理任务已启动: retentionDays={}", retentionDays);
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    void cleanup() {
        try {
            int deleted = conversationHistoryService.cleanupOldMessages(retentionDays);
            if (deleted > 0) {
                log.info("[ConversationCleanup] 清理完成: deleted={}", deleted);
            }
        } catch (Exception e) {
            log.warn("[ConversationCleanup] 清理失败: {}", e.getMessage());
        }
    }
}
