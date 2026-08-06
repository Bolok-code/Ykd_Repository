package ykd.ykd.job.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ykd.ykd.job.browser.LiepinAutomationGateway;
import ykd.ykd.job.config.LiepinProperties;
import ykd.ykd.job.mapper.LiepinJobCampaignMapper;
import ykd.ykd.job.mapper.LiepinJobPostingMapper;
import ykd.ykd.job.mapper.LiepinJobTaskMapper;
import ykd.ykd.job.model.LiepinApplicationResult;
import ykd.ykd.job.model.LiepinJobPosting;
import ykd.ykd.job.model.LiepinJobTask;
import ykd.ykd.job.model.LiepinResume;
import ykd.ykd.job.model.LiepinTaskStatus;
import ykd.ykd.job.service.LiepinApplicationService;
import ykd.ykd.job.service.LiepinJobMatchService;
import ykd.ykd.job.service.LiepinResumeService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归测试：单个搜索任务支持逐个投递多个候选岗位。
 * 成功（{@code SUCCEEDED}）后可继续确认其他岗位；已投递（{@code SUBMITTED}）岗位不得重复投递。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LiepinJobTaskManagerTest {

    private static final String USER_ID = "u1";
    private static final long TASK_ID = 9L;

    @Mock private LiepinProperties properties;
    @Mock private LiepinResumeService resumeService;
    @Mock private LiepinJobTaskMapper taskMapper;
    @Mock private LiepinJobPostingMapper postingMapper;
    @Mock private LiepinJobCampaignMapper campaignMapper;
    @Mock private LiepinApplicationService applicationService;
    @Mock private LiepinJobMatchService matchService;
    @Mock private LiepinAutomationGateway browser;

    private LiepinJobTaskManager manager;

    @BeforeEach
    void setUp() {
        manager = new LiepinJobTaskManager(properties, resumeService, taskMapper, postingMapper,
                campaignMapper, applicationService, matchService, browser);
        when(resumeService.find(USER_ID)).thenReturn(resume());
        // 默认让后台投递返回失败，避免无关场景被异步执行干扰
        when(browser.applyAndSendResume(any(), any(), any(), any()))
                .thenReturn(LiepinApplicationResult.failed("忽略"));
    }

    @AfterEach
    void tearDown() {
        manager.stop();
    }

    @Test
    void confirmApplicationAllowedWhenTaskSucceeded() {
        when(taskMapper.findLatestByUser(USER_ID)).thenReturn(task(LiepinTaskStatus.SUCCEEDED));
        when(postingMapper.findByTaskId(TASK_ID))
                .thenReturn(List.of(posting(1L, "java开发工程师", "CANDIDATE")));

        String result = manager.confirmApplication(USER_ID, 1);

        assertThat(result).contains("已确认候选岗位 #1");
        verify(postingMapper).updateStatus(1L, "SUBMITTING");
    }

    @Test
    void submittedPostingRejectedForDuplicateApplication() {
        when(taskMapper.findLatestByUser(USER_ID)).thenReturn(task(LiepinTaskStatus.SUCCEEDED));
        when(postingMapper.findByTaskId(TASK_ID))
                .thenReturn(List.of(posting(1L, "java开发工程师", "SUBMITTED")));

        String result = manager.confirmApplication(USER_ID, 1);

        assertThat(result).contains("已投递成功，请选择其他候选岗位");
        verify(postingMapper, never()).updateStatus(anyLong(), anyString());
    }

    @Test
    void successfulApplicationKeepsTaskOpenForNextCandidate() {
        LiepinJobTask task = task(LiepinTaskStatus.SUCCEEDED);
        when(taskMapper.findLatestByUser(USER_ID)).thenReturn(task);
        LiepinJobPosting p1 = posting(1L, "java开发工程师", "CANDIDATE");
        LiepinJobPosting p2 = posting(2L, "后端java工程师", "CANDIDATE");
        when(postingMapper.findByTaskId(TASK_ID)).thenReturn(List.of(p1, p2));
        when(browser.applyAndSendResume(any(), any(), any(), any()))
                .thenReturn(LiepinApplicationResult.success("投递成功"));
        // 模拟 DB 状态回写，让去重检查能看到"已投递"
        when(postingMapper.updateStatus(eq(1L), anyString())).thenAnswer(inv -> {
            p1.setStatus(inv.getArgument(1));
            return 1;
        });

        String first = manager.confirmApplication(USER_ID, 1);
        assertThat(first).contains("已确认候选岗位 #1");

        // 后台投递成功：岗位标记 SUBMITTED，任务保持 SUCCEEDED 并带进度
        verify(postingMapper, timeout(3000)).updateStatus(1L, "SUBMITTED");
        verify(taskMapper, timeout(3000))
                .updateStatus(eq(TASK_ID), eq(USER_ID), eq("SUCCEEDED"), contains("已投递 1/2"));

        // 同一任务可继续投递下一个候选
        String second = manager.confirmApplication(USER_ID, 2);
        assertThat(second).contains("已确认候选岗位 #2");
    }

    private LiepinJobTask task(LiepinTaskStatus status) {
        LiepinJobTask task = new LiepinJobTask();
        task.setId(TASK_ID);
        task.setUserId(USER_ID);
        task.setStatus(status.name());
        return task;
    }

    private LiepinJobPosting posting(long id, String jobName, String status) {
        LiepinJobPosting posting = new LiepinJobPosting();
        posting.setId(id);
        posting.setTaskId(TASK_ID);
        posting.setJobName(jobName);
        posting.setCompanyName("测试公司");
        posting.setStatus(status);
        return posting;
    }

    private LiepinResume resume() {
        LiepinResume resume = new LiepinResume();
        resume.setUserId(USER_ID);
        resume.setContent("Java 后端简历");
        return resume;
    }
}
