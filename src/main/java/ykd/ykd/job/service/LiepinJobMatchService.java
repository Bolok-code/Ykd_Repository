package ykd.ykd.job.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import ykd.ykd.job.config.LiepinProperties;
import ykd.ykd.job.model.LiepinJobPosting;

/**
 * AI 职位匹配服务。
 *
 * <p>使用 DeepSeek 模型对简历和职位进行智能评分。
 * 模型以严格的 JSON 格式返回分数（0-100）、理由和招呼语。
 * 匹配失败时降级为默认 60 分和默认招呼语，不阻断投递流程。</p>
 *
 * <h3>评分逻辑</h3>
 * 输入：简历内容（截断至 8000 字）+ 职位信息（名称、公司、城市、薪资、描述截断至 5000 字）。
 * 输出：{@code {"score": N, "reason": "...", "greeting": "..."}}。
 */
@Slf4j
@Service
public class LiepinJobMatchService {
    private final ChatClient matchClient;
    private final ObjectMapper objectMapper;
    private final LiepinProperties properties;

    public LiepinJobMatchService(DeepSeekChatModel model, ObjectMapper objectMapper, LiepinProperties properties) {
        this.matchClient = ChatClient.builder(model)
                .defaultSystem("""
                        你是招聘岗位匹配助手。只根据简历和岗位信息评分，不得虚构经历。
                        必须仅输出 JSON：{"score":0到100整数,"reason":"一句话理由","greeting":"真实、简短的沟通语"}。
                        沟通语不得声称简历中不存在的技能或经验。
                        """)
                .build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 对职位进行 AI 匹配评分，结果回填到 posting 对象中。
     *
     * @param resumeContent 简历纯文本内容
     * @param posting       目标职位
     * @return {@code true} AI 评分成功；{@code false} 异常降级
     */
    public boolean enrich(String resumeContent, LiepinJobPosting posting) {
        try {
            String prompt = """
                    简历：
                    %s

                    岗位：%s
                    公司：%s
                    城市：%s
                    薪资：%s
                    岗位描述：
                    %s
                    """.formatted(
                    limit(resumeContent, 8000), posting.getJobName(), posting.getCompanyName(),
                    posting.getCity(), posting.getSalary(), limit(posting.getDescription(), 5000));
            String raw = matchClient.prompt().user(prompt).call().content();
            JsonNode json = objectMapper.readTree(extractJson(raw));
            posting.setMatchScore(Math.max(0, Math.min(100, json.path("score").asInt(60))));
            posting.setMatchReason(json.path("reason").asText("岗位与简历存在一定匹配度"));
            posting.setGreeting(json.path("greeting").asText(properties.getDefaultGreeting()));
            return true;
        } catch (Exception e) {
            log.warn("[LiepinJob] AI 匹配失败，使用降级评分: job={}, error={}", posting.getJobName(), e.getMessage());
            posting.setMatchScore(60);
            posting.setMatchReason("AI 匹配暂时不可用，请人工查看岗位描述");
            posting.setGreeting(properties.getDefaultGreeting());
            return false;
        }
    }

    /**
     * 从模型原始输出中提取 JSON（可能包含 Markdown 代码块包裹）。
     */
    private String extractJson(String raw) {
        if (raw == null) {
            return "{}";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return start >= 0 && end > start ? raw.substring(start, end + 1) : "{}";
    }

    /** 截断过长文本，防止超出模型 token 限制。 */
    private String limit(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
