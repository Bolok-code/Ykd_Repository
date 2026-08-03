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

    /**
     * Embedding 批量请求时每次最多发送多少条文本。
     * DashScope text-embedding-v3 单次最多 25 条。
     */
    private int embedBatchSize = 25;

    /**
     * 是否在启动时自动扫描目录并入库文档。
     */
    private boolean autoIngestEnabled = false;

    /**
     * 自动入库扫描的目录路径（相对于工作目录）。
     */
    private String autoIngestDir = "./work/rag/auto-ingest";
}
