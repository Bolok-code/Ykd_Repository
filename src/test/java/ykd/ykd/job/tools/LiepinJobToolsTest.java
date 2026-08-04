package ykd.ykd.job.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ykd.ykd.job.service.LiepinCampaignService;
import ykd.ykd.job.service.LiepinResumeService;
import ykd.ykd.job.task.LiepinJobTaskManager;
import ykd.ykd.processor.UserContext;
import ykd.ykd.skill.session.SkillSessionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归测试：取消任务/停止投递后不得自动退出猎聘技能模式，
 * 否则紧接着的"停掉他"等跟进命令会失去猎聘工具，导致 LLM 误判。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LiepinJobToolsTest {

    @Mock
    private UserContext userContext;
    @Mock
    private LiepinResumeService resumeService;
    @Mock
    private LiepinCampaignService campaignService;
    @Mock
    private LiepinJobTaskManager taskManager;
    @Mock
    private SkillSessionManager skillSessionManager;

    private LiepinJobTools tools() {
        return new LiepinJobTools(userContext, resumeService, campaignService, taskManager, skillSessionManager);
    }

    @Test
    void cancelTaskShouldKeepSkillSession() {
        when(userContext.getCurrentUserId()).thenReturn("u1");
        when(taskManager.cancelLatest("u1")).thenReturn("已取消猎聘求职任务 #9。");

        String result = tools().cancelLiepinJobTask();

        assertThat(result).startsWith("已取消");
        // 关键断言：取消成功绝不能移除技能会话
        verify(skillSessionManager, never()).remove("u1");
    }

    @Test
    void stopCampaignShouldKeepSkillSession() {
        when(userContext.getCurrentUserId()).thenReturn("u1");
        when(campaignService.stopLatest("u1")).thenReturn("已停止投递计划 #2。");

        String result = tools().stopLiepinAutoApplyCampaign();

        assertThat(result).startsWith("已停止");
        verify(skillSessionManager, never()).remove("u1");
    }

    @Test
    void exitLiepinSkillShouldRemoveSession() {
        when(userContext.getCurrentUserId()).thenReturn("u1");

        String result = tools().exitLiepinSkill();

        assertThat(result).contains("已退出猎聘技能模式");
        verify(skillSessionManager).remove("u1");
    }

    @Test
    void cancelWithoutActiveSessionShouldNotRemove() {
        // taskManager 返回 null 场景其实不会发生（mock 默认返回 null 也会进入 remove 判断之前 return）
        when(userContext.getCurrentUserId()).thenReturn("u1");
        when(taskManager.cancelLatest("u1")).thenReturn("暂无可取消的猎聘求职任务。");

        String result = tools().cancelLiepinJobTask();

        assertThat(result).contains("暂无可取消");
        verify(skillSessionManager, never()).remove("u1");
    }
}
