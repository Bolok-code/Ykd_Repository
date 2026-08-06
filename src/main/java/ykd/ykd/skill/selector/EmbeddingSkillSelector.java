package ykd.ykd.skill.selector;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.ykd.rag.service.EmbeddingService;
import ykd.ykd.skill.model.SkillDefinition;
import ykd.ykd.skill.registry.SkillRegistry;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Embedding 语义相似度的 Skill 选择器。
 *
 * <p>启动时预计算每个 Skill 的 description embedding，
 * 请求时将用户消息 embedding 与所有 Skill 逐一比对余弦相似度。</p>
 *
 * <ul>
 *   <li>分数 {@code >= ACTIVATE_CONFIDENCE}：高置信命中，直接激活技能模式。</li>
 *   <li>分数落在 {@code [SIMILARITY_THRESHOLD, ACTIVATE_CONFIDENCE)}：模糊命中，
 *       返回 {@code CONFIRM}，由上层先向用户确认意图，避免"取消任务"这类泛化消息误入技能模式。
 *       例外：模糊命中的同时消息含强特征词（如"猎聘""投递""岗位"）时，
 *       用户意图已明确，以关键词高置信命中为准直接激活，避免确认轮无限循环。</li>
 *   <li>低于阈值：继续走关键词子串匹配兜底。</li>
 * </ul>
 *
 * <p>当 Embedding API 不可用时（如 API key 未配置），
 * 自动降级为从 Skill description 中提取特征词的子串匹配，
 * 确保 Skill 路由不会因外部依赖故障而完全失效。</p>
 */
@Slf4j
@Component
public class EmbeddingSkillSelector implements SkillSelector {

    /** 模糊命中下限：低于此值不算命中（继续关键词兜底） */
    private static final double SIMILARITY_THRESHOLD = 0.45;
    /** 高置信阈值：达到此分数才直接激活，无需用户确认 */
    private static final double ACTIVATE_CONFIDENCE = 0.60;
    private static final int MAX_EMBED_TEXT_LENGTH = 512;

    /**
     * 降级关键词匹配时忽略的通用词。
     * 这些词出现在 Skill description 中，但由于过于通用，单独命中不能作为路由依据。
     */
    private static final Set<String> KEYWORD_BLACKLIST = Set.of("简历");

    /**
     * 允许以 2 字子串命中的强特征词。
     * 长片段里的 2 字子串大多过泛（"使用""测试"等），只对真正高区分的词放行，
     * 例如用户说"猎聘"基本必然指向投递技能。
     */
    private static final Set<String> STRONG_2CHAR_KEYWORDS = Set.of("猎聘", "投递", "岗位");

    /** Embedding 构建失败后的冷启动重试间隔，避免 API 不可用时每条消息都触发重建 */
    private static final long REBUILD_RETRY_INTERVAL_MS = 60_000L;

    private final SkillRegistry skillRegistry;
    private final EmbeddingService embeddingService;

    /** Skill name → description embedding vector */
    private final Map<String, float[]> skillEmbeddings = new ConcurrentHashMap<>();
    private long lastBuildAttemptAt = 0L;

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

        if (skillEmbeddings.isEmpty()) {
            log.warn("[SkillSelector] 所有 Skill 的 Embedding 计算均失败，"
                    + "将降级为 description 关键词匹配。请检查 Embedding API 配置。");
        } else {
            log.info("[SkillSelector] Embedding 缓存构建完成: cached={}/total={}",
                    skillEmbeddings.size(), enabled.size());
        }
    }

    /**
     * Embedding 缓存为空时（启动构建失败/API 暂时不可用）按间隔懒重试。
     * 构建成功一次后缓存非空，后续不会再触发。
     */
    private synchronized void maybeRebuildEmbeddingCache() {
        long now = System.currentTimeMillis();
        if (now - lastBuildAttemptAt < REBUILD_RETRY_INTERVAL_MS) {
            return;
        }
        lastBuildAttemptAt = now;
        try {
            buildEmbeddingCache();
        } catch (Exception e) {
            log.warn("[SkillSelector] Embedding 缓存重建失败，稍后重试: {}", e.getMessage());
        }
    }

    @Override
    public SkillSelectionResult select(String message) {
        if (message == null || message.isBlank()) {
            return SkillSelectionResult.none();
        }

        // 启动时 Embedding 构建失败（缓存为空）时懒重试，避免 Skill 路由永久退化
        if (skillEmbeddings.isEmpty()) {
            maybeRebuildEmbeddingCache();
        }

        String trimmed = message.trim();

        // 第一层：Embedding 语义匹配（API 可用时），高置信激活 / 模糊命中待确认
        if (!skillEmbeddings.isEmpty()) {
            SkillSelectionResult result = selectByEmbedding(trimmed);
            if (result.isActivate()) return result;
            if (result.isConfirm()) {
                // 模糊命中：若消息含强特征词（"猎聘""投递""岗位"），说明用户意图明确
                //（如"使用猎聘 投递java 杭州 10k左右的工作"），以关键词高置信命中为准，
                // 否则保留 CONFIRM 交由上层先向用户确认，避免"取消任务"这类泛化消息误激活。
                SkillSelectionResult keywordHit = selectByKeywords(trimmed);
                if (keywordHit.isActivate()) return keywordHit;
                return result;
            }
            // Embedding 未过阈值 → 继续走降级兜底
        }

        // 第二层：关键词子串匹配（永远可用，兜底保障，强命中直接激活）
        return selectByKeywords(trimmed);
    }

    // ---- Embedding 主路径 ----

    private SkillSelectionResult selectByEmbedding(String trimmed) {
        String embedTarget = trimmed.length() > MAX_EMBED_TEXT_LENGTH
                ? trimmed.substring(0, MAX_EMBED_TEXT_LENGTH)
                : trimmed;

        float[] msgVector;
        try {
            String msgEmbeddingJson = embeddingService.embed(embedTarget);
            msgVector = embeddingService.jsonToFloatArray(msgEmbeddingJson);
        } catch (Exception e) {
            log.error("[SkillSelector] 用户消息 Embedding 失败，降级为关键词匹配: {}",
                    e.getMessage());
            return SkillSelectionResult.none();
        }

        if (msgVector.length == 0) {
            log.warn("[SkillSelector] 用户消息 Embedding 为空向量，降级为关键词匹配");
            return SkillSelectionResult.none();
        }

        String bestName = null;
        double bestScore = 0.0;

        for (var entry : skillEmbeddings.entrySet()) {
            double score = embeddingService.cosineSimilarity(msgVector, entry.getValue());
            if (score > bestScore) {
                bestScore = score;
                bestName = entry.getKey();
            }
        }

        if (bestName != null && bestScore >= SIMILARITY_THRESHOLD) {
            SkillDefinition skill = skillRegistry.findEnabledByName(bestName).orElse(null);
            if (skill == null) return SkillSelectionResult.none();
            if (bestScore >= ACTIVATE_CONFIDENCE) {
                log.info("[SkillSelector] Embedding 高置信命中: name={}, score={}, preview={}",
                        bestName, String.format("%.3f", bestScore), preview(trimmed));
                return SkillSelectionResult.activate(skill);
            }
            // 模糊命中：不直接激活，交由上层先向用户确认意图
            log.info("[SkillSelector] Embedding 模糊命中(待确认): name={}, score={}, preview={}",
                    bestName, String.format("%.3f", bestScore), preview(trimmed));
            return SkillSelectionResult.confirm(skill);
        }

        // INFO 级别方便排查阈值是否合理
        log.info("[SkillSelector] Embedding 未过阈值: bestScore={}, threshold={}, preview={}",
                String.format("%.3f", bestScore), SIMILARITY_THRESHOLD, preview(trimmed));
        return SkillSelectionResult.none();
    }

    // ---- 降级：关键词子串匹配 ----

    /**
     * 从 Skill description 中提取特征词做子串匹配。
     * 将 description 按标点切分，对用户消息做 contains 检查。
     * 如果多个 Skill 都匹配，返回匹配片段最长的那个。
     */
    private SkillSelectionResult selectByKeywords(String message) {
        String normalized = message.toLowerCase(Locale.ROOT);

        SkillDefinition bestSkill = null;
        int bestMatchLen = 0;

        for (SkillDefinition skill : skillRegistry.findAllEnabled()) {
            int matchLen = matchScore(skill, normalized);
            if (matchLen > bestMatchLen) {
                bestMatchLen = matchLen;
                bestSkill = skill;
            }
        }

        if (bestSkill != null && bestMatchLen > 0) {
            log.info("[SkillSelector] 关键词降级命中: name={}, matchLen={}, preview={}",
                    bestSkill.name(), bestMatchLen, preview(message));
            return SkillSelectionResult.activate(bestSkill);
        }

        log.debug("[SkillSelector] 关键词降级也未命中: preview={}", preview(message));
        return SkillSelectionResult.none();
    }

    /**
     * 计算 Skill 的 description + name 与用户消息的匹配程度。
     * 先将 description 按标点切分为片段：
     * <ul>
     *   <li>完整片段命中直接返回其长度（整段短语置信度高）</li>
     *   <li>长片段（>3 字）未完整命中时检查 3~4 字子串</li>
     *   <li>2 字子串太泛（"使用""测试"易误命中），只放行强特征词（如"猎聘"）</li>
     * </ul>
     *
     * @return 用户消息中包含的最长匹配片段长度，0 表示未命中
     */
    private int matchScore(SkillDefinition skill, String normalizedMessage) {
        String source = (skill.name() + " " + skill.description()).toLowerCase(Locale.ROOT);

        // 按常见分隔符切分
        String[] segments = source.split("[，。！？、；：（）\\s,.!?;:()\\[\\]【】\\-—…]+");

        int maxLen = 0;
        for (String segment : segments) {
            String trimmed = segment.trim();
            if (trimmed.length() < 2) continue;

            // 完整片段命中：3 字及以上片段是明确信号；2 字片段太泛（"取消""暂停""查询"），
            // 只有强特征词（"猎聘""投递""岗位"）才放行，避免"取消任务"这类消息误命中。
            if (!KEYWORD_BLACKLIST.contains(trimmed)
                    && (trimmed.length() >= 3 || STRONG_2CHAR_KEYWORDS.contains(trimmed))
                    && normalizedMessage.contains(trimmed)) {
                maxLen = Math.max(maxLen, trimmed.length());
                continue;
            }

            // 长片段未完整命中 → 检查 3~4 字子串
            if (trimmed.length() > 3) {
                int subMax = Math.min(4, trimmed.length() - 1);
                for (int len = subMax; len >= 3; len--) {
                    for (int i = 0; i <= trimmed.length() - len; i++) {
                        String sub = trimmed.substring(i, i + len);
                        if (KEYWORD_BLACKLIST.contains(sub)) continue;
                        if (normalizedMessage.contains(sub)) {
                            maxLen = Math.max(maxLen, len);
                        }
                    }
                }
                // 2 字强特征词单独放行，避免"使用""测试"等泛词误命中
                for (String kw : STRONG_2CHAR_KEYWORDS) {
                    if (!KEYWORD_BLACKLIST.contains(kw)
                            && trimmed.contains(kw)
                            && normalizedMessage.contains(kw)) {
                        maxLen = Math.max(maxLen, 2);
                    }
                }
            }
        }

        return maxLen;
    }

    // ---- 公共工具方法 ----

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
