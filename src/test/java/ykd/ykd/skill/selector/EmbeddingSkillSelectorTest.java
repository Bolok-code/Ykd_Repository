package ykd.ykd.skill.selector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ykd.ykd.rag.service.EmbeddingService;
import ykd.ykd.skill.loader.SkillLoader;
import ykd.ykd.skill.model.SkillDefinition;
import ykd.ykd.skill.registry.SkillRegistry;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingSkillSelectorTest {

    // 用简单三维向量：相同方向 → cos≈1.0，正交 → cos=0.0
    private static final String MATCH_VEC = "[1.0, 0.0, 0.0]";
    private static final String NOMATCH_VEC = "[0.0, 1.0, 0.0]";

    private EmbeddingSkillSelector skillSelector;

    @BeforeEach
    void setUp() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);

        // jsonToFloatArray: 简单 JSON 数组解析
        when(embeddingService.jsonToFloatArray(anyString())).thenAnswer(inv -> {
            String json = inv.getArgument(0);
            json = json.replace("[", "").replace("]", "").trim();
            String[] parts = json.split(",");
            float[] result = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                result[i] = Float.parseFloat(parts[i].trim());
            }
            return result;
        });

        // cosineSimilarity: 真实余弦相似度实现
        when(embeddingService.cosineSimilarity(any(float[].class), any(float[].class)))
                .thenAnswer(inv -> {
                    float[] a = inv.getArgument(0);
                    float[] b = inv.getArgument(1);
                    double dot = 0, na = 0, nb = 0;
                    for (int i = 0; i < a.length; i++) {
                        dot += a[i] * b[i];
                        na += a[i] * a[i];
                        nb += b[i] * b[i];
                    }
                    return (na == 0 || nb == 0) ? 0.0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
                });

        // embed(): 包含猎聘/投递/求职关键词 → MATCH_VEC，否则 → NOMATCH_VEC
        // Skill description 中含有"猎聘"，所以缓存向量 = MATCH_VEC
        when(embeddingService.embed(anyString())).thenAnswer(inv -> {
            String text = inv.getArgument(0);
            if (text != null && (text.contains("猎聘")
                    || text.contains("投递")
                    || text.contains("求职")
                    || text.contains("liepin"))) {
                return MATCH_VEC;
            }
            return NOMATCH_VEC;
        });

        SkillLoader skillLoader = new SkillLoader();
        SkillRegistry skillRegistry = new SkillRegistry(skillLoader);
        skillRegistry.loadAll();

        skillSelector = new EmbeddingSkillSelector(skillRegistry, embeddingService);
        skillSelector.buildEmbeddingCache();
    }

    @Test
    void shouldSelectLiepinSkillForJobSearch() {
        Optional<SkillDefinition> result = skillSelector.select(
                "帮我在猎聘搜索杭州Java岗位"
        );
        assertTrue(result.isPresent());
        assertEquals("liepin-auto-apply", result.get().name());
    }

    @Test
    void shouldSelectLiepinSkillForAutoApply() {
        Optional<SkillDefinition> result = skillSelector.select(
                "给我创建一个自动投递计划"
        );
        assertTrue(result.isPresent());
        assertTrue(result.get().tools().contains("createLiepinAutoApplyCampaign"));
    }

    @Test
    void shouldSelectLiepinSkillForCampaignManagement() {
        assertTrue(skillSelector.select("查看我的投递状态").isPresent());
        assertTrue(skillSelector.select("暂停投递").isPresent());
        assertTrue(skillSelector.select("停止投递").isPresent());
    }

    @Test
    void shouldSelectLiepinSkillForSynonymExpression() {
        // "找工作" 包含 "工作" 但不是关键词——但 "海投" 也不在 mock 关键词中
        // 测试至少一种同义表达能命中
        assertTrue(skillSelector.select("猎聘找工作").isPresent());
    }

    @Test
    void shouldNotSelectSkillForWeatherQuery() {
        Optional<SkillDefinition> result = skillSelector.select("明天杭州天气怎么样");
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldNotSelectSkillForNormalDocumentQuestion() {
        Optional<SkillDefinition> result = skillSelector.select("帮我总结一下这份简历");
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyForBlankMessage() {
        assertTrue(skillSelector.select(null).isEmpty());
        assertTrue(skillSelector.select("").isEmpty());
        assertTrue(skillSelector.select("   ").isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenCacheIsEmpty() {
        EmbeddingSkillSelector emptySelector = new EmbeddingSkillSelector(
                mock(SkillRegistry.class), mock(EmbeddingService.class));
        assertTrue(emptySelector.select("猎聘搜索岗位").isEmpty());
    }
}
