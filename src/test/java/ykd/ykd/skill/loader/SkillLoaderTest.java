package ykd.ykd.skill.loader;

import org.junit.jupiter.api.Test;
import ykd.ykd.skill.model.SkillDefinition;

import static org.junit.jupiter.api.Assertions.*;

class SkillLoaderTest {

    private final SkillLoader skillLoader = new SkillLoader();

    @Test
    void shouldLoadLiepinAutoApplySkill() {
        SkillDefinition skill = skillLoader.loadFromClasspath(
                "skills/liepin-auto-apply/SKILL.md"
        );

        // 验证基础元数据
        assertEquals("liepin-auto-apply", skill.name());
        assertEquals("1.0.0", skill.version());
        assertTrue(skill.enabled());

        // 验证描述成功读取
        assertNotNull(skill.description());
        assertFalse(skill.description().isBlank());

        // 验证工具列表成功读取
        assertFalse(skill.tools().isEmpty());
        assertTrue(skill.tools().contains("openLiepinLogin"));
        assertTrue(skill.tools().contains("searchLiepinJobs"));
        assertTrue(skill.tools().contains(
                "createLiepinAutoApplyCampaign"
        ));
        assertTrue(skill.tools().contains(
                "startLiepinAutoApplyCampaign"
        ));

        // 验证 Markdown 正文成功读取
        assertNotNull(skill.instructions());
        assertTrue(skill.instructions().contains(
                "# 猎聘自动求职技能"
        ));
        assertTrue(skill.instructions().contains(
                "未经用户明确确认，不得启动自动投递"
        ));
    }
}