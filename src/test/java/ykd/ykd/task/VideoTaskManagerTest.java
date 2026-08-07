package ykd.ykd.task;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import ykd.ykd.llm.service.VideoService;
import ykd.ykd.processor.ProcessResult;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 视频任务内存回归测试：
 * 任务完成/失败后必须从任务表移除，超时未完成的任务必须被定期清理，
 * 防止 Map 无限增长。
 */
class VideoTaskManagerTest {

    @Test
    void completedTaskIsRemovedFromMap() {
        VideoService videoService = mock(VideoService.class);
        JsonNode result = mock(JsonNode.class);
        JsonNode metadata = mock(JsonNode.class);
        JsonNode url = mock(JsonNode.class);
        when(result.path("metadata")).thenReturn(metadata);
        when(metadata.path("url")).thenReturn(url);
        when(url.asText()).thenReturn("http://example.com/video.mp4");
        when(videoService.downloadVideo("http://example.com/video.mp4")).thenReturn(new byte[]{1, 2, 3});

        VideoTaskManager manager = new VideoTaskManager(videoService);
        AtomicReference<ProcessResult> captured = new AtomicReference<>();
        manager.setOnCompleted(captured::set);
        manager.registerTask("task-1", "user-1");

        @SuppressWarnings("unchecked")
        Map<String, VideoTaskManager.VideoTask> tasks =
                (Map<String, VideoTaskManager.VideoTask>) ReflectionTestUtils.getField(manager, "tasks");
        VideoTaskManager.VideoTask task = tasks.get("task-1");
        ReflectionTestUtils.invokeMethod(manager, "handleCompleted", task, result);

        // 完成后任务必须被移除，避免 Map 泄漏
        assertThat(tasks).isEmpty();
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().type()).isEqualTo(ProcessResult.Type.VIDEO);
    }

    @Test
    void stalePendingTaskIsSwept() {
        VideoTaskManager manager = new VideoTaskManager(mock(VideoService.class));
        manager.registerTask("task-stale", "user-1");

        @SuppressWarnings("unchecked")
        Map<String, VideoTaskManager.VideoTask> tasks =
                (Map<String, VideoTaskManager.VideoTask>) ReflectionTestUtils.getField(manager, "tasks");
        // 把任务改成 2 小时前创建，模拟视频服务端一直不返回终态
        tasks.put("task-stale", new VideoTaskManager.VideoTask(
                "task-stale", "user-1", System.currentTimeMillis() - 2 * 60 * 60 * 1000L));

        ReflectionTestUtils.invokeMethod(manager, "sweepStaleTasks");

        assertThat(tasks).isEmpty();
    }
}
