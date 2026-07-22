package ykd.ykd.processor;

import tools.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ykd.ykd.llm.service.VideoService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 视频任务管理器，负责后台轮询任务状态，完成后将结果入队。
 *
 * <p>配合 {@link ykd.ykd.llm.tools.VideoTools} 使用：</p>
 * <ol>
 *   <li>VideoTools 提交任务后调用 {@link #registerTask(String, String)} 注册</li>
 *   <li>内建守护线程每 5 秒轮询一次未完成任务</li>
 *   <li>检测到 {@code completed} 时下载视频，通过回调入队等待 BotService 发送</li>
 * </ol>
 *
 * <p>不直接持有 ILinkClient，完成后通过 {@link Consumer} 回传
 * {@link ProcessResult#video(byte[])}，由 {@code WeixinBotService} 统一发送。</p>
 */
@Component
public class VideoTaskManager {
    private static final Logger log = LoggerFactory.getLogger(VideoTaskManager.class);

    private final VideoService videoService;
    private final Map<String, VideoTask> tasks = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    /** 完成回调，由 MessageProcessor 在初始化时设置为入队操作 */
    private Consumer<ProcessResult> onCompleted;

    public VideoTaskManager(VideoService videoService) {
        this.videoService = videoService;
    }

    @PostConstruct
    public void start() {
        Thread poller = new Thread(this::pollLoop, "video-poller");
        poller.setDaemon(true);
        poller.start();
        log.info("[VideoTaskManager] 后台轮询已启动");
    }

    @PreDestroy
    public void stop() {
        running = false;
    }

    /**
     * 设置完成回调，由 MessageProcessor 在初始化时调用。
     */
    public void setOnCompleted(Consumer<ProcessResult> callback) {
        this.onCompleted = callback;
    }

    /**
     * 注册待处理任务。
     */
    public void registerTask(String taskId, String userId) {
        tasks.put(taskId, new VideoTask(taskId, userId, System.currentTimeMillis()));
        log.info("[VideoTaskManager] 注册任务: taskId={}, userId={}", taskId, userId);
    }

    // === 后台轮询 ===

    private void pollLoop() {
        while (running) {
            try {
                for (Map.Entry<String, VideoTask> entry : tasks.entrySet()) {
                    VideoTask task = entry.getValue();
                    if (task.status != Status.PENDING) continue;

                    try {
                        JsonNode result = videoService.checkStatus(task.taskId);
                        String state = result.path("status").asText();

                        if ("completed".equals(state)) {
                            handleCompleted(task, result);
                        } else if ("failed".equals(state)) {
                            task.status = Status.FAILED;
                            log.warn("[VideoTaskManager] 任务失败: taskId={}", task.taskId);
                        }
                    } catch (Exception e) {
                        log.warn("[VideoTaskManager] 查询异常: taskId={}", task.taskId, e);
                    }
                }
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[VideoTaskManager] 轮询异常", e);
            }
        }
    }

    private void handleCompleted(VideoTask task, JsonNode result) {
        task.status = Status.COMPLETED;
        log.info("[VideoTaskManager] 任务完成，完整响应: {}", result);
        String videoUrl = result.path("metadata").path("url").asText();

        if (videoUrl.isEmpty()) {
            log.warn("[VideoTaskManager] 任务完成但无视频URL: taskId={}, response={}", task.taskId, result);
            return;
        }
        log.info("[VideoTaskManager] 视频生成完成: taskId={}, url={}", task.taskId, videoUrl);

        byte[] videoData = videoService.downloadVideo(videoUrl);
        if (videoData == null) return;

        if (onCompleted != null) {
            onCompleted.accept(ProcessResult.video(videoData, task.userId));
            log.info("[VideoTaskManager] 视频已入队待发送: userId={}, size={}KB",
                    task.userId, videoData.length / 1024);
        } else {
            log.warn("[VideoTaskManager] 完成回调未设置: taskId={}", task.taskId);
        }
    }

    enum Status { PENDING, COMPLETED, FAILED }

    static class VideoTask {
        final String taskId;
        final String userId;
        final long createdAt;
        volatile Status status = Status.PENDING;

        VideoTask(String taskId, String userId, long createdAt) {
            this.taskId = taskId;
            this.userId = userId;
            this.createdAt = createdAt;
        }
    }
}
