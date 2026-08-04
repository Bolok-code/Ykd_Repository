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

    // 三类向量：猎聘/知识库/无关，确保 Embedding 相似度能正确区分
    private static final String LIEPIN_VEC = "[1.0, 0.0, 0.0]";
    private static final String KB_VEC = "[0.0, 1.0, 0.0]";
    private static final String OTHER_VEC = "[0.0, 0.0, 1.0]";

    private EmbeddingSkillSelector skillSelector;

    // === Embedding 主路径测试（mock API 正常） ===

    @BeforeEach
    void setUp() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);

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

        when(embeddingService.embed(anyString())).thenAnswer(inv -> {
            String text = inv.getArgument(0);
            if (text != null && (text.contains("猎聘")
                    || text.contains("投递")
                    || text.contains("求职")
                    || text.contains("liepin"))) {
                return LIEPIN_VEC;
            }
            if (text != null && (text.contains("知识库")
                    || text.contains("资料")
                    || text.contains("文档"))) {
                return KB_VEC;
            }
            return OTHER_VEC;
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
                "帮我在猎聘搜索杭州Java岗位");
        assertTrue(result.isPresent());
        assertEquals("liepin-auto-apply", result.get().name());
    }

    @Test
    void shouldSelectLiepinSkillForAutoApply() {
        Optional<SkillDefinition> result = skillSelector.select("给我创建一个自动投递计划");
        assertTrue(result.isPresent());
        assertTrue(result.get().tools().contains("createLiepinAutoApplyCampaign"));
    }

    @Test
    void shouldSelectLiepinSkillForCampaignManagement() {
        assertTrue(skillSelector.select("查看我的投递状态").isPresent());
        assertTrue(skillSelector.select("暂停投递").isPresent());
        assertTrue(skillSelector.select("停止投递").isPresent());
    }

    // === 知识库 Skill 路由测试 ===

    @Test
    void shouldSelectKnowledgeBaseSkillForStoreDocument() {
        Optional<SkillDefinition> result = skillSelector.select("存入我们知识库");
        assertTrue(result.isPresent());
        assertEquals("knowledge-base", result.get().name());
    }

    @Test
    void shouldSelectKnowledgeBaseSkillForDocQA() {
        assertTrue(skillSelector.select("根据我的文档总结关键信息").isPresent());
        assertTrue(skillSelector.select("资料里提到了什么").isPresent());
    }

    // === 负例：不应该命中任何 Skill ===

    @Test
    void shouldNotSelectSkillForWeatherQuery() {
        assertTrue(skillSelector.select("明天杭州天气怎么样").isEmpty());
    }

    @Test
    void shouldNotSelectSkillForNormalDocumentQuestion() {
        // "帮助我总结一下这份文件" vs 天气——两者都跟任何 Skill description 不匹配
        assertTrue(skillSelector.select("帮我总结一下这份简历").isEmpty());
    }

    @Test
    void shouldReturnEmptyForBlankMessage() {
        assertTrue(skillSelector.select(null).isEmpty());
        assertTrue(skillSelector.select("").isEmpty());
        assertTrue(skillSelector.select("   ").isEmpty());
    }

    // === 降级路径测试（Embedding API 不可用） ===

    @Test
    void shouldFallbackToKeywordsWhenEmbeddingFails() {
        // 构造一个 Embedding 全部失败的 Selector
        EmbeddingService failingEmbedding = mock(EmbeddingService.class);
        when(failingEmbedding.embed(anyString()))
                .thenThrow(new RuntimeException("API unavailable"));

        SkillLoader skillLoader = new SkillLoader();
        SkillRegistry skillRegistry = new SkillRegistry(skillLoader);
        skillRegistry.loadAll();

        EmbeddingSkillSelector fallbackSelector = new EmbeddingSkillSelector(
                skillRegistry, failingEmbedding);
        fallbackSelector.buildEmbeddingCache(); // 缓存为空

        // "猎聘" 出现在 description 中 → 降级关键词匹配应命中
        assertTrue(fallbackSelector.select("帮我在猎聘搜索杭州Java岗位").isPresent());

        // "投递" 出现在 description 中 → 应命中
        assertTrue(fallbackSelector.select("帮我投递简历").isPresent());
        assertTrue(fallbackSelector.select("查看投递状态").isPresent());

        // 不相关消息 → 不应命中
        assertTrue(fallbackSelector.select("明天天气怎么样").isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenCacheIsEmptyAndNoKeywordMatch() {
        EmbeddingSkillSelector emptySelector = new EmbeddingSkillSelector(
                mock(SkillRegistry.class), mock(EmbeddingService.class));
        assertTrue(emptySelector.select("猎聘搜索岗位").isEmpty());
    }
}
