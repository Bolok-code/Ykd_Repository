package ykd.ykd.task;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.ykd.processor.ProcessResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 图片批处理管理器，将短时间内连续发送的多张图片合并为一批一起处理。
 *
 * <p>与 {@link ReminderTaskManager} 采用相同的 ScheduledExecutorService 模式：</p>
 * <ol>
 *   <li>用户发图片 → 缓冲并启动 3 秒倒计时</li>
 *   <li>3 秒内又来图片 → 加入缓冲，重置倒计时</li>
 *   <li>倒计时到期 → 合并所有图片，通过回调交给 MessageProcessor 处理</li>
 * </ol>
 */
@Slf4j
@Component
public class ImageBatchManager {

    private static final long BATCH_DELAY_MS = 3000;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    /** 批处理执行池：LLM 调用（含工具）可能耗时较长，不能占用唯一的调度线程，
     *  否则一个用户的批次卡住会导致其他用户的批次全部排队（head-of-line 阻塞） */
    private final ExecutorService processorPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "image-batch-process");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, List<String>> imageBuffer = new ConcurrentHashMap<>();
    private final Map<String, String> textBuffer = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingFutures = new ConcurrentHashMap<>();
    private Consumer<ImageBatch> onBatchReady;

    public void setOnBatchReady(Consumer<ImageBatch> callback) {
        this.onBatchReady = callback;
    }

    /**
     * 将一张图片加入缓冲区，重置倒计时。
     *
     * @param userId      用户 ID
     * @param imageDataUri 图片 base64 data URI
     * @param text         该条消息附带的文字（可为 null）
     */
    public void addImage(String userId, String imageDataUri, String text) {
        synchronized (this) {
            imageBuffer.computeIfAbsent(userId, k -> new ArrayList<>()).add(imageDataUri);
            if (text != null && !text.isBlank()) {
                textBuffer.put(userId, text);
            }

            ScheduledFuture<?> existing = pendingFutures.remove(userId);
            if (existing != null) {
                existing.cancel(false);
            }

            ScheduledFuture<?> future = scheduler.schedule(() -> flush(userId), BATCH_DELAY_MS, TimeUnit.MILLISECONDS);
            pendingFutures.put(userId, future);
        }

        log.info("[ImageBatch] 图片已缓冲: userId={}, bufferedCount={}", userId, imageBuffer.get(userId).size());
    }

    private void flush(String userId) {
        List<String> images;
        String text;
        synchronized (this) {
            images = imageBuffer.remove(userId);
            text = textBuffer.remove(userId);
            pendingFutures.remove(userId);
        }

        if (images != null && !images.isEmpty() && onBatchReady != null) {
            log.info("[ImageBatch] 批次触发: userId={}, imageCount={}", userId, images.size());
            // 放到独立执行池，调度线程立即空闲，其他用户的倒计时不受影响
            processorPool.execute(() -> {
                try {
                    onBatchReady.accept(new ImageBatch(userId, images, text));
                } catch (Exception e) {
                    log.error("[ImageBatch] 批次处理回调异常: userId={}, error={}", userId, e.getMessage(), e);
                }
            });
        }
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
        processorPool.shutdownNow();
    }

    public record ImageBatch(String userId, List<String> imageUris, String text) {}
}