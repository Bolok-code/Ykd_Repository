package ykd.ykd.skill.selector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ykd.ykd.skill.loader.SkillLoader;
import ykd.ykd.skill.model.SkillDefinition;
import ykd.ykd.skill.registry.SkillRegistry;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class KeywordSkillSelectorTest {

    private KeywordSkillSelector skillSelector;

    @BeforeEach
    void setUp() {
        SkillLoader skillLoader = new SkillLoader();

        SkillRegistry skillRegistry =
                new SkillRegistry(skillLoader);

        skillRegistry.loadAll();

        skillSelector =
                new KeywordSkillSelector(skillRegistry);
    }

    @Test
    void shouldSelectLiepinSkillForJobSearch() {
        Optional<SkillDefinition> result =
                skillSelector.select(
                        "帮我在猎聘搜索杭州Java岗位"
                );

        assertTrue(result.isPresent());

        assertEquals(
                "liepin-auto-apply",
                result.get().name()
        );
    }

    @Test
    void shouldSelectLiepinSkillForAutoApply() {
        Optional<SkillDefinition> result =
                skillSelector.select(
                        "给我创建一个自动投递计划"
                );

        assertTrue(result.isPresent());

        assertTrue(
                result.get().tools().contains(
                        "createLiepinAutoApplyCampaign"
                )
        );
    }

    @Test
    void shouldSelectLiepinSkillForCampaignManagement() {
        assertTrue(
                skillSelector.select(
                        "查看我的投递状态"
                ).isPresent()
        );

        assertTrue(
                skillSelector.select(
                        "暂停投递"
                ).isPresent()
        );

        assertTrue(
                skillSelector.select(
                        "停止投递"
                ).isPresent()
        );
    }

    @Test
    void shouldNotSelectSkillForWeatherQuery() {
        Optional<SkillDefinition> result =
                skillSelector.select(
                        "明天杭州天气怎么样"
                );

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldNotSelectSkillForNormalDocumentQuestion() {
        Optional<SkillDefinition> result =
                skillSelector.select(
                        "帮我总结一下这份简历"
                );

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyForBlankMessage() {
        assertTrue(skillSelector.select(null).isEmpty());
        assertTrue(skillSelector.select("").isEmpty());
        assertTrue(skillSelector.select("   ").isEmpty());
    }
}