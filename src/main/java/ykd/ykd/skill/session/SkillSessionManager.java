package ykd.ykd.skill.session;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill 活跃会话管理（userId → 会话）。
 *
 * <p>由 {@code LlmServiceImpl} 读写实现会话保持（10 分钟滑动 TTL），
 * 猎聘工具在任务取消、计划永久停止时清除会话，让用户无需等 TTL 过期即可回到普通对话。
 */
@Component
public class SkillSessionManager {

    public record SkillSession(String skillName, long lastActiveAt) {}

    private final Map<String, SkillSession> activeSkills = new ConcurrentHashMap<>();

    /** 激活或续期 Skill 会话。 */
    public void activate(String userId, String skillName) {
        activeSkills.put(userId, new SkillSession(skillName, System.currentTimeMillis()));
    }

    /** 查询活跃会话；无则返回 {@code null}。 */
    public SkillSession get(String userId) {
        return activeSkills.get(userId);
    }

    /** 清除活跃会话并返回它；无则返回 {@code null}。 */
    public SkillSession remove(String userId) {
        return activeSkills.remove(userId);
    }
}
