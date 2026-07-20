package ykd.ykd.llm.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import ykd.ykd.llm.service.VideoService;
import ykd.ykd.processor.VideoTaskManager;

/**
 * 视频生成工具，通过 {@link VideoService} 调用 Agnes AI 视频 API，
 * 由 LLM 自主决定何时调用。
 *
 * <p>与图片生成不同，视频生成是异步的：提交任务后由 {@link VideoTaskManager} 后台轮询，
 * 完成后主动推送到微信，不阻塞消息处理流程。</p>
 */
@Slf4j
@Component
public class VideoTools {

    private final VideoService videoService;
    private final VideoTaskManager videoTaskManager;

    public VideoTools(VideoService videoService, VideoTaskManager videoTaskManager) {
        this.videoService = videoService;
        this.videoTaskManager = videoTaskManager;
    }

    /**
     * 生成视频。当用户要求生成视频、创作视频、制作视频时由 LLM 调用此工具。
     *
     * @param prompt 视频内容描述，如"一只猫在跳舞"
     * @return 提交结果提示文本
     */
    @Tool(description = "生成视频。当用户要求生成视频、创作视频、制作视频时调用此工具")
    public String generateVideo(
            @ToolParam(description = "视频内容描述，如'一只猫在跳舞'") String prompt) {

        long start = System.currentTimeMillis();
        String userId = videoTaskManager.getCurrentUserId();
        log.info("[VideoTools] 被调用: prompt={}, userId={}", prompt, userId);

        try {
            String taskId = videoService.submitTask(prompt);
            videoTaskManager.registerTask(taskId, userId);

            long elapsed = System.currentTimeMillis() - start;
            log.info("[VideoTools] 提交成功: taskId={}, elapsed={}ms", taskId, elapsed);

            return "🎬 视频正在生成中，生成完成后会自动发送给您！";
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[VideoTools] 提交失败: elapsed={}ms, prompt={}, error={}", elapsed, prompt, e.getMessage(), e);
            return "视频生成提交失败：" + e.getMessage();
        }
    }
}
