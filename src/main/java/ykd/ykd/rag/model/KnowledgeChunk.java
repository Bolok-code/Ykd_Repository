package ykd.ykd.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识库文本片段实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeChunk {
    /**
     * 主键 ID
     */
    private Long id;
    
    /**
     * 所属文档 ID
     */
    private Long documentId;
    
    /**
     * 片段在文档中的索引位置
     */
    private Integer chunkIndex;
    
    /**
     * 文本内容
     */
    private String content;
    
    /**
     * 向量表示（JSON 格式存储）
     */
    private String embedding;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
