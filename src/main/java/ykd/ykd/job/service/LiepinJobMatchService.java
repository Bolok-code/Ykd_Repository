package ykd.ykd.job.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import ykd.ykd.job.config.LiepinProperties;
import ykd.ykd.job.model.LiepinJobPosting;

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

    public void enrich(String resumeContent, LiepinJobPosting posting) {
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
        } catch (Exception e) {
            log.warn("[LiepinJob] AI 匹配失败，使用降级评分: job={}, error={}", posting.getJobName(), e.getMessage());
            posting.setMatchScore(60);
            posting.setMatchReason("AI 匹配暂时不可用，请人工查看岗位描述");
            posting.setGreeting(properties.getDefaultGreeting());
        }
    }

    private String extractJson(String raw) {
        if (raw == null) {
            return "{}";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return start >= 0 && end > start ? raw.substring(start, end + 1) : "{}";
    }

    private String limit(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}