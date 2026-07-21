package ykd.ykd.llm.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import ykd.ykd.exception.ErrorCode;
import ykd.ykd.llm.service.VideoService;
import ykd.ykd.processor.UserContext;
import ykd.ykd.processor.VideoTaskManager;

@Slf4j
@Component
public class VideoTools {

    private final VideoService videoService;
    private final VideoTaskManager videoTaskManager;
    private final UserContext userContext;

    public VideoTools(VideoService videoService, VideoTaskManager videoTaskManager,
                      UserContext userContext) {
        this.videoService = videoService;
        this.videoTaskManager = videoTaskManager;
        this.userContext = userContext;
    }

    @Tool(description = "生成视频。当用户要求生成视频、创作视频、制作视频时调用此工具")
    public String generateVideo(
            @ToolParam(description = "视频内容描述，如'一只猫在跳舞'") String prompt) {

        long start = System.currentTimeMillis();
        String userId = userContext.getCurrentUserId();
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
            return "❌ " + ErrorCode.VIDEO_SUBMIT_FAILED.getDefaultMessage();
        }
    }
}
