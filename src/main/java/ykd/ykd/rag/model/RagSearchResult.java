package ykd.ykd.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG 检索结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagSearchResult {
    /**
     * 文档 ID
     */
    private Long documentId;
    
    /**
     * 文档名称
     */
    private String fileName;
    
    /**
     * 片段内容
     */
    private String content;
    
    /**
     * 相似度得分
     */
    private Double score;
    
    /**
     * 片段索引
     */
    private Integer chunkIndex;
}
