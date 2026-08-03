package ykd.ykd.skill.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import ykd.ykd.job.service.LiepinCampaignService;
import ykd.ykd.job.service.LiepinResumeService;
import ykd.ykd.job.task.LiepinJobTaskManager;
import ykd.ykd.job.tools.LiepinJobTools;
import ykd.ykd.processor.UserContext;
import ykd.ykd.skill.loader.SkillLoader;
import ykd.ykd.skill.session.SkillSessionManager;
import ykd.ykd.skill.model.SkillDefinition;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SkillToolResolverTest {

    private SkillToolResolver skillToolResolver;
    private SkillDefinition liepinSkill;

    @BeforeEach
    void setUp() {
        LiepinJobTools liepinJobTools = new LiepinJobTools(
                mock(UserContext.class),
                mock(LiepinResumeService.class),
                mock(LiepinCampaignService.class),
                mock(LiepinJobTaskManager.class),
                mock(SkillSessionManager.class)
        );

        skillToolResolver =
                new SkillToolResolver(liepinJobTools);

        liepinSkill = new SkillLoader()
                .loadFromClasspath(
                        "skills/liepin-auto-apply/SKILL.md"
                );
    }

    @Test
    void shouldResolveAllToolsDeclaredByLiepinSkill() {
        ToolCallback[] callbacks =
                skillToolResolver.resolve(liepinSkill);

        List<String> resolvedNames =
                Arrays.stream(callbacks)
                        .map(callback ->
                                callback
                                        .getToolDefinition()
                                        .name()
                        )
                        .toList();

        assertEquals(
                liepinSkill.tools().size(),
                callbacks.length
        );

        assertEquals(
                liepinSkill.tools(),
                resolvedNames
        );
    }

    @Test
    void shouldContainKnownLiepinTools() {
        assertTrue(
                skillToolResolver.contains(
                        "openLiepinLogin"
                )
        );

        assertTrue(
                skillToolResolver.contains(
                        "searchLiepinJobs"
                )
        );

        assertTrue(
                skillToolResolver.contains(
                        "startLiepinAutoApplyCampaign"
                )
        );
    }

    @Test
    void shouldRejectUnknownToolDeclaredBySkill() {
        SkillDefinition invalidSkill =
                new SkillDefinition(
                        "invalid-skill",
                        "用于验证错误工具配置",
                        "1.0.0",
                        true,
                        List.of("notExistingTool"),
                        "测试说明"
                );

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> skillToolResolver.resolve(
                                invalidSkill
                        )
                );

        assertTrue(
                exception.getMessage()
                        .contains("notExistingTool")
        );
    }
}
