package ykd.ykd.skill.selector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ykd.ykd.rag.service.EmbeddingService;
import ykd.ykd.skill.loader.SkillLoader;
import ykd.ykd.skill.model.SkillDefinition;
import ykd.ykd.skill.registry.SkillRegistry;

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
    private EmbeddingService embeddingService;

    // === Embedding 主路径测试（mock API 正常） ===

    @BeforeEach
    void setUp() {
        embeddingService = mock(EmbeddingService.class);

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
    void shouldActivateLiepinSkillForJobSearch() {
        SkillSelectionResult result = skillSelector.select("帮我在猎聘搜索杭州Java岗位");
        assertActivate(result, "liepin-auto-apply");
    }

    @Test
    void shouldActivateLiepinSkillForAutoApply() {
        SkillSelectionResult result = skillSelector.select("给我创建一个自动投递计划");
        assertActivate(result, "liepin-auto-apply");
        assertTrue(result.skill().tools().contains("createLiepinAutoApplyCampaign"));
    }

    @Test
    void shouldActivateLiepinSkillForCampaignManagement() {
        assertActivate(skillSelector.select("查看我的投递状态"), "liepin-auto-apply");
        assertActivate(skillSelector.select("暂停投递"), "liepin-auto-apply");
        assertActivate(skillSelector.select("停止投递"), "liepin-auto-apply");
    }

    // === 知识库 Skill 路由测试 ===

    @Test
    void shouldActivateKnowledgeBaseSkillForStoreDocument() {
        SkillSelectionResult result = skillSelector.select("存入我们知识库");
        assertActivate(result, "knowledge-base");
    }

    @Test
    void shouldActivateKnowledgeBaseSkillForDocQA() {
        assertActivate(skillSelector.select("根据我的文档总结关键信息"), "knowledge-base");
        assertActivate(skillSelector.select("资料里提到了什么"), "knowledge-base");
    }

    // === 模糊命中：应返回 CONFIRM 而非直接激活 ===

    @Test
    void shouldConfirmSkillWhenScoreInAmbiguousRange() {
        // "取消任务" 与猎聘 description 的相似度落在 0.45~0.60 区间 → 待确认，不直接激活
        when(embeddingService.embed("取消任务")).thenReturn("[0.5, 0.0, 0.866]");
        SkillSelectionResult result = skillSelector.select("取消任务");
        assertConfirm(result, "liepin-auto-apply");
    }

    @Test
    void shouldActivateLiepinWhenExplicitKeywordDespiteFuzzyScore() {
        // 回归：生产日志 2026-08-05 中"使用猎聘 投递java 杭州 10k左右的工作"的语义相似度
        // 落在模糊区间（0.537），但消息含"猎聘""投递"强特征词，必须直接激活；
        // 否则确认轮无限循环，用户始终拿不到猎聘工具，模型会误答"没有该工具"。
        when(embeddingService.embed("使用猎聘 投递java 杭州 10k左右的工作"))
                .thenReturn("[0.55, 0.0, 0.835]");
        SkillSelectionResult result = skillSelector.select("使用猎聘 投递java 杭州 10k左右的工作");
        assertActivate(result, "liepin-auto-apply");
    }

    @Test
    void shouldNotActivateSkillForLowScore() {
        // 相似度低于 0.45 且无关键词 → NONE
        when(embeddingService.embed("明天杭州天气怎么样")).thenReturn("[0.3, 0.0, 0.954]");
        assertNone(skillSelector.select("明天杭州天气怎么样"));
    }

    // === 负例：不应该命中任何 Skill ===

    @Test
    void shouldNotSelectSkillForWeatherQuery() {
        assertNone(skillSelector.select("明天杭州天气怎么样"));
    }

    @Test
    void shouldNotSelectSkillForNormalDocumentQuestion() {
        // "帮助我总结一下这份文件" vs 天气——两者都跟任何 Skill description 不匹配
        assertNone(skillSelector.select("帮我总结一下这份简历"));
    }

    @Test
    void shouldReturnNoneForBlankMessage() {
        assertNone(skillSelector.select(null));
        assertNone(skillSelector.select(""));
        assertNone(skillSelector.select("   "));
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
        assertActivate(fallbackSelector.select("帮我在猎聘搜索杭州Java岗位"), "liepin-auto-apply");

        // "投递" 出现在 description 中 → 应命中
        assertActivate(fallbackSelector.select("帮我投递简历"), "liepin-auto-apply");
        assertActivate(fallbackSelector.select("查看投递状态"), "liepin-auto-apply");

        // 不相关消息 → 不应命中
        assertNone(fallbackSelector.select("明天天气怎么样"));
    }

    @Test
    void shouldNotMatchGenericTwoCharWordInFallback() {
        EmbeddingService failingEmbedding = mock(EmbeddingService.class);
        when(failingEmbedding.embed(anyString()))
                .thenThrow(new RuntimeException("API unavailable"));

        SkillLoader skillLoader = new SkillLoader();
        SkillRegistry skillRegistry = new SkillRegistry(skillLoader);
        skillRegistry.loadAll();

        EmbeddingSkillSelector fallbackSelector = new EmbeddingSkillSelector(
                skillRegistry, failingEmbedding);
        fallbackSelector.buildEmbeddingCache(); // 缓存为空，走关键词降级

        // "使用""功能" 这类 2 字泛词嵌入在长句中，不应误命中任何 Skill
        assertNone(fallbackSelector.select("这个功能怎么使用"));
        assertNone(fallbackSelector.select("请问这个功能怎么使用"));
    }

    @Test
    void shouldReturnNoneWhenCacheIsEmptyAndNoKeywordMatch() {
        EmbeddingSkillSelector emptySelector = new EmbeddingSkillSelector(
                mock(SkillRegistry.class), mock(EmbeddingService.class));
        assertNone(emptySelector.select("猎聘搜索岗位"));
    }

    // === 辅助断言 ===

    private static void assertActivate(SkillSelectionResult result, String skillName) {
        assertEquals(SkillSelectionResult.ResultType.ACTIVATE, result.type());
        assertNotNull(result.skill());
        assertEquals(skillName, result.skill().name());
    }

    private static void assertConfirm(SkillSelectionResult result, String skillName) {
        assertEquals(SkillSelectionResult.ResultType.CONFIRM, result.type());
        assertNotNull(result.skill());
        assertEquals(skillName, result.skill().name());
    }

    private static void assertNone(SkillSelectionResult result) {
        assertEquals(SkillSelectionResult.ResultType.NONE, result.type());
        assertNull(result.skill());
    }
}
