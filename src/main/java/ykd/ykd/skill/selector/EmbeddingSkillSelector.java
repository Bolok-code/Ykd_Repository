package ykd.ykd.skill.selector;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.ykd.rag.service.EmbeddingService;
import ykd.ykd.skill.model.SkillDefinition;
import ykd.ykd.skill.registry.SkillRegistry;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Embedding 语义相似度的 Skill 选择器。
 *
 * 启动时预计算每个 Skill 的 description embedding，
 * 请求时将用户消息 embedding 与所有 Skill 逐一比对余弦相似度，
 * 超过阈值的最高分 Skill 即为命中。
 */
@Slf4j
@Component
public class EmbeddingSkillSelector implements SkillSelector {

    private static final double SIMILARITY_THRESHOLD = 0.6;
    private static final int MAX_EMBED_TEXT_LENGTH = 512;

    private final SkillRegistry skillRegistry;
    private final EmbeddingService embeddingService;

    /** Skill name → description embedding vector */
    private final Map<String, float[]> skillEmbeddings = new ConcurrentHashMap<>();

    public EmbeddingSkillSelector(
            SkillRegistry skillRegistry,
            EmbeddingService embeddingService) {
        this.skillRegistry = skillRegistry;
        this.embeddingService = embeddingService;
    }

    @PostConstruct
    void buildEmbeddingCache() {
        List<SkillDefinition> enabled = skillRegistry.findAllEnabled();
        if (enabled.isEmpty()) {
            log.info("[SkillSelector] 无已启用 Skill，跳过 Embedding 缓存构建");
            return;
        }

        for (SkillDefinition skill : enabled) {
            try {
                String embedText = buildEmbeddingText(skill);
                String embeddingJson = embeddingService.embed(embedText);
                float[] vector = embeddingService.jsonToFloatArray(embeddingJson);
                if (vector.length > 0) {
                    skillEmbeddings.put(skill.name(), vector);
                    log.info("[SkillSelector] Skill Embedding 已缓存: name={}, dim={}",
                            skill.name(), vector.length);
                }
            } catch (Exception e) {
                log.error("[SkillSelector] Skill Embedding 计算失败: name={}, error={}",
                        skill.name(), e.getMessage());
            }
        }

        log.info("[SkillSelector] Embedding 缓存构建完成: cached={}/total={}",
                skillEmbeddings.size(), enabled.size());
    }

    @Override
    public Optional<SkillDefinition> select(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }

        if (skillEmbeddings.isEmpty()) {
            log.warn("[SkillSelector] Embedding 缓存为空，无法匹配");
            return Optional.empty();
        }

        // 截断过长消息，减少 Embedding API 开销
        String trimmed = message.trim();
        String embedTarget = trimmed.length() > MAX_EMBED_TEXT_LENGTH
                ? trimmed.substring(0, MAX_EMBED_TEXT_LENGTH)
                : trimmed;

        float[] msgVector;
        try {
            String msgEmbeddingJson = embeddingService.embed(embedTarget);
            msgVector = embeddingService.jsonToFloatArray(msgEmbeddingJson);
        } catch (Exception e) {
            log.error("[SkillSelector] 用户消息 Embedding 失败: {}", e.getMessage());
            return Optional.empty();
        }

        if (msgVector.length == 0) {
            log.warn("[SkillSelector] 用户消息 Embedding 为空向量");
            return Optional.empty();
        }

        // 遍历所有 Skill 找最高相似度
        String bestName = null;
        double bestScore = 0.0;

        for (var entry : skillEmbeddings.entrySet()) {
            double score = embeddingService.cosineSimilarity(msgVector, entry.getValue());
            log.debug("[SkillSelector] 相似度: skill={}, score={:.4f}", entry.getKey(), score);
            if (score > bestScore) {
                bestScore = score;
                bestName = entry.getKey();
            }
        }

        if (bestName != null && bestScore >= SIMILARITY_THRESHOLD) {
            log.info("[SkillSelector] Embedding 命中: name={}, score={}, preview={}",
                    bestName, String.format("%.3f", bestScore), preview(trimmed));
            return skillRegistry.findEnabledByName(bestName);
        }

        log.debug("[SkillSelector] 未命中任何 Skill: bestScore={}, preview={}",
                bestScore > 0 ? String.format("%.3f", bestScore) : "N/A", preview(trimmed));
        return Optional.empty();
    }

    /**
     * 构造用于 Embedding 匹配的文本。
     * 拼接 Skill 名称和描述，让名称中的特征词权重更高。
     */
    private String buildEmbeddingText(SkillDefinition skill) {
        return skill.name() + ": " + skill.description();
    }

    private String preview(String text) {
        if (text == null) return "null";
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }
}
