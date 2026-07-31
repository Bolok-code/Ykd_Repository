package ykd.ykd.skill.selector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.ykd.skill.model.SkillDefinition;
import ykd.ykd.skill.registry.SkillRegistry;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
public class KeywordSkillSelector implements SkillSelector {

    private static final String LIEPIN_SKILL_NAME = "liepin-auto-apply";

    /**
     * 需要词边界匹配的短关键词（长度 ≤ 3）。
     * 长关键词本身足够特异，允许子串匹配。
     */
    private static final Set<String> SHORT_KEYWORDS = Set.of(
            "liepin", "求职", "投简历"
    );

    private static final List<String> LIEPIN_KEYWORDS = List.of(
            "猎聘",
            "liepin",
            "找工作",
            "求职",
            "求职简历",
            "保存简历",
            "设置简历",
            "搜索岗位",
            "岗位搜索",
            "匹配岗位",
            "候选岗位",
            "投简历",
            "投递简历",
            "自动投递",
            "开始投递",
            "暂停投递",
            "停止投递",
            "投递计划",
            "投递状态",
            "投递进度",
            "投递记录"
    );

    /**
     * 当文本超过此长度时，仅搜索首尾窗口，中间大概率是文档正文，不应参与意图路由。
     */
    private static final int SEARCH_WINDOW_LENGTH = 200;
    private static final int SEARCH_TAIL_LENGTH = 100;

    private final SkillRegistry skillRegistry;

    public KeywordSkillSelector(
            SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @Override
    public Optional<SkillDefinition> select(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }

        String normalizedMessage = message
                .trim()
                .toLowerCase(Locale.ROOT);

        // 长文本只搜首尾窗口，避免文档正文中的关键词误命中
        String searchTarget = extractSearchWindow(normalizedMessage);

        boolean liepinMatched = LIEPIN_KEYWORDS.stream()
                .anyMatch(kw -> matchesKeyword(searchTarget, kw));

        if (!liepinMatched) {
            log.debug(
                    "[SkillSelector] 未命中Skill: messagePreview={}",
                    preview(normalizedMessage)
            );
            return Optional.empty();
        }

        Optional<SkillDefinition> skill =
                skillRegistry.findEnabledByName(
                        LIEPIN_SKILL_NAME
                );

        if (skill.isPresent()) {
            log.info(
                    "[SkillSelector] 命中Skill: name={}, messagePreview={}",
                    skill.get().name(),
                    preview(normalizedMessage)
            );
        } else {
            log.warn(
                    "[SkillSelector] 已识别猎聘意图，但Skill不存在或未启用"
            );
        }

        return skill;
    }

    /**
     * 检查关键词是否与文本匹配。
     * 短关键词需要词边界，防止"求职"匹配到毫不相关的长字符串内部。
     */
    private boolean matchesKeyword(String text, String keyword) {
        int idx = text.indexOf(keyword);
        if (idx < 0) {
            return false;
        }

        if (!SHORT_KEYWORDS.contains(keyword)) {
            return true; // 长关键词直接子串匹配
        }

        // 短关键词需要词边界：前一个字符必须是分隔符或文本开头
        boolean leftBoundary = idx == 0 || isBoundary(text.charAt(idx - 1));

        // 后一个字符必须是分隔符或文本结尾
        int afterIdx = idx + keyword.length();
        boolean rightBoundary = afterIdx >= text.length() || isBoundary(text.charAt(afterIdx));

        return leftBoundary && rightBoundary;
    }

    /**
     * 判断字符是否为自然分词边界。
     */
    private boolean isBoundary(char c) {
        return Character.isWhitespace(c)
                || c == '\n' || c == '\r'
                || c == '，' || c == '。' || c == '！' || c == '？'
                || c == '、' || c == '；' || c == '：'
                || c == '（' || c == '）' || c == '【' || c == '】'
                || c == '(' || c == ')' || c == '[' || c == ']'
                || c == '.' || c == ',' || c == '!' || c == '?'
                || c == '-' || c == '—' || c == '…'
                || c == ' ' || c == '\t';
    }

    /**
     * 对于长文本，只提取首尾两段用于关键词匹配，
     * 中间部分大概率是文档正文，不应参与意图路由。
     */
    private String extractSearchWindow(String text) {
        if (text.length() <= SEARCH_WINDOW_LENGTH + SEARCH_TAIL_LENGTH) {
            return text;
        }
        String head = text.substring(0, SEARCH_WINDOW_LENGTH);
        String tail = text.substring(text.length() - SEARCH_TAIL_LENGTH);
        return head + "\n" + tail;
    }

    private String preview(String text) {
        if (text == null) return "null";
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }
}
