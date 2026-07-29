package ykd.ykd.skill.registry;

import org.junit.jupiter.api.Test;
import ykd.ykd.skill.loader.SkillLoader;
import ykd.ykd.skill.model.SkillDefinition;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SkillRegistryTest {

    @Test
    void shouldLoadAndRegisterLiepinSkill() {
        // 手动创建依赖，不启动完整Spring项目
        SkillLoader skillLoader = new SkillLoader();
        SkillRegistry skillRegistry =
                new SkillRegistry(skillLoader);

        // 手动加载resources/skills下的Skill
        skillRegistry.loadAll();

        // 验证至少加载了一个Skill
        assertTrue(skillRegistry.size() >= 1);

        // 根据名称查询猎聘Skill
        Optional<SkillDefinition> result =
                skillRegistry.findEnabledByName(
                        "liepin-auto-apply"
                );

        assertTrue(result.isPresent());

        SkillDefinition skill = result.get();

        assertEquals("liepin-auto-apply", skill.name());
        assertTrue(skill.enabled());
        assertFalse(skill.tools().isEmpty());
        assertTrue(skill.tools().contains(
                "searchLiepinJobs"
        ));
    }

    @Test
    void shouldReturnAllEnabledSkills() {
        SkillRegistry skillRegistry =
                new SkillRegistry(new SkillLoader());

        skillRegistry.loadAll();

        List<SkillDefinition> skills =
                skillRegistry.findAllEnabled();

        assertFalse(skills.isEmpty());

        assertTrue(
                skills.stream().anyMatch(
                        skill -> skill.name().equals(
                                "liepin-auto-apply"
                        )
                )
        );
    }

    @Test
    void shouldReturnEmptyWhenSkillDoesNotExist() {
        SkillRegistry skillRegistry =
                new SkillRegistry(new SkillLoader());

        skillRegistry.loadAll();

        Optional<SkillDefinition> result =
                skillRegistry.findEnabledByName(
                        "not-exist-skill"
                );

        assertTrue(result.isEmpty());
    }
}