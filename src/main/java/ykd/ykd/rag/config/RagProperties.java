package ykd.ykd.rag.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {
    /**
     * 是否启用 RAG 功能。
     */
    private boolean enabled = true;

    /**
     * 单个文本片段最大字符数。
     */
    private int chunkSize = 600;

    /**
     * 相邻文本片段重复字符数，防止语义被截断。
     */
    private int chunkOverlap = 100;

    /**
     * 每次检索返回最相关的片段数量。
     */
    private int topK = 5;

    /**
     * 最多交给大模型多少字符的检索上下文。
     */
    private int maxContextChars = 6000;

    /**
     * 相似度最低阈值。
     * 第一版先设为 0，等接入真实 Embedding 模型后再调整。
     */
    private double similarityThreshold = 0.0;
}
