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
     * TokenTextSplitter 每个片段的 token 数上限。
     * 中文约 1.5 字符/token，300 token ≈ 450 汉字。
     */
    private int chunkSize = 300;

    /**
     * TokenTextSplitter 片段最少字符数，低于此值会被合并到相邻片段。
     */
    private int minChunkSizeChars = 50;

    /**
     * TokenTextSplitter 单文档最大片段数，防止超大文档撑爆。
     */
    private int maxNumChunks = 100;

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
     * OpenAI / DashScope text-embedding-v3 单次最多 10 条。
     */
    private int embedBatchSize = 10;

    /**
     * 是否在启动时自动扫描目录并入库文档。
     */
    private boolean autoIngestEnabled = false;

    /**
     * 自动入库扫描的目录路径（相对于工作目录）。
     */
    private String autoIngestDir = "./work/rag/auto-ingest";
}
