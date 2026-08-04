package ykd.ykd.skill.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import ykd.ykd.job.tools.LiepinJobTools;
import ykd.ykd.rag.tools.KnowledgeBaseTools;
import ykd.ykd.skill.model.SkillDefinition;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将Skill中声明的工具名称，
 * 转换为Spring AI可以执行的ToolCallback。
 */
@Slf4j
@Component
public class SkillToolResolver {

    /**
     * key是Tool名称，value是真正可执行的ToolCallback。
     */
    private final Map<String, ToolCallback> toolCallbacks;

    public SkillToolResolver(
            LiepinJobTools liepinJobTools,
            KnowledgeBaseTools knowledgeBaseTools) {

        Map<String, ToolCallback> discoveredTools =
                new LinkedHashMap<>();

        // 扫描猎聘工具
        ToolCallback[] liepinCallbacks =
                ToolCallbacks.from(liepinJobTools);
        for (ToolCallback callback : liepinCallbacks) {
            String toolName = callback.getToolDefinition().name();
            ToolCallback existing = discoveredTools.putIfAbsent(toolName, callback);
            if (existing != null) {
                throw new IllegalStateException("发现重复的Tool名称：" + toolName);
            }
        }

        // 扫描知识库工具
        ToolCallback[] kbCallbacks =
                ToolCallbacks.from(knowledgeBaseTools);
        for (ToolCallback callback : kbCallbacks) {
            String toolName = callback.getToolDefinition().name();
            ToolCallback existing = discoveredTools.putIfAbsent(toolName, callback);
            if (existing != null) {
                throw new IllegalStateException("发现重复的Tool名称：" + toolName);
            }
        }

        this.toolCallbacks =
                Map.copyOf(discoveredTools);

        log.info(
                "[SkillToolResolver] Tool加载完成: count={}, names={}",
                toolCallbacks.size(),
                toolCallbacks.keySet()
        );
    }

    /**
     * 根据Skill声明的工具名称，返回允许使用的工具。
     */
    public ToolCallback[] resolve(
            SkillDefinition skill) {

        List<String> missingTools =
                skill.tools()
                        .stream()
                        .filter(toolName ->
                                !toolCallbacks.containsKey(
                                        toolName
                                ))
                        .toList();

        /*
         * SKILL.md写了不存在的Tool时直接报错，
         * 避免系统静默忽略配置错误。
         */
        if (!missingTools.isEmpty()) {
            throw new IllegalStateException(
                    "Skill“"
                            + skill.name()
                            + "”引用了不存在的Tool："
                            + missingTools
            );
        }

        ToolCallback[] resolvedTools =
                skill.tools()
                        .stream()
                        .map(toolCallbacks::get)
                        .toArray(ToolCallback[]::new);

        log.info(
                "[SkillToolResolver] Skill工具解析完成: skill={}, tools={}",
                skill.name(),
                Arrays.stream(resolvedTools)
                        .map(callback ->
                                callback
                                        .getToolDefinition()
                                        .name()
                        )
                        .toList()
        );

        return resolvedTools;
    }

    /**
     * 查询已经注册的Tool数量。
     */
    public int size() {
        return toolCallbacks.size();
    }

    /**
     * 判断指定Tool是否已经注册。
     */
    public boolean contains(String toolName) {
        return toolCallbacks.containsKey(toolName);
    }
}