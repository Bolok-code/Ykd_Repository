package ykd.ykd.skill.selector;

import ykd.ykd.skill.model.SkillDefinition;

import java.util.Optional;

/**
 * 根据用户消息选择需要使用的Skill。
 */
public interface SkillSelector {
    /**
     * @param message 用户发送的消息
     * @return 命中的Skill，没有命中则返回空
     */
    Optional<SkillDefinition> select(String message);
}
