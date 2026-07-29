package ykd.ykd.skill.selector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.ykd.skill.model.SkillDefinition;
import ykd.ykd.skill.registry.SkillRegistry;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Component
public class KeywordSkillSelector implements SkillSelector {
    private static final String LIEPIN_SKILL_NAME = "liepin-auto-apply";
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

        boolean liepinMatched = LIEPIN_KEYWORDS.stream()
                .anyMatch(normalizedMessage::contains);

        if (!liepinMatched) {
            log.debug(
                    "[SkillSelector] 未命中Skill: message={}",
                    normalizedMessage
            );
            return Optional.empty();
        }

        Optional<SkillDefinition> skill =
                skillRegistry.findEnabledByName(
                        LIEPIN_SKILL_NAME
                );

        if (skill.isPresent()) {
            log.info(
                    "[SkillSelector] 命中Skill: name={}, message={}",
                    skill.get().name(),
                    normalizedMessage
            );
        } else {
            log.warn(
                    "[SkillSelector] 已识别猎聘意图，但Skill不存在或未启用"
            );
        }

        return skill;
    }
}
