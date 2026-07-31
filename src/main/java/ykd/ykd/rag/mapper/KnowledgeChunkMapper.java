package ykd.ykd.rag.mapper;

import org.apache.ibatis.annotations.*;
import ykd.ykd.rag.model.KnowledgeChunk;

import java.util.List;

/**
 * 知识库文本片段 Mapper
 */
@Mapper
public interface KnowledgeChunkMapper {
    
    /**
     * 插入文本片段
     */
    @Insert("""
        INSERT INTO knowledge_chunk 
        (document_id, chunk_index, content, embedding, created_at)
        VALUES (#{documentId}, #{chunkIndex}, #{content}, #{embedding}, #{createdAt})
    """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(KnowledgeChunk chunk);
    
    /**
     * 批量插入文本片段
     */
    @Insert("""
        <script>
        INSERT INTO knowledge_chunk 
        (document_id, chunk_index, content, embedding, created_at)
        VALUES 
        <foreach collection="chunks" item="chunk" separator=",">
        (#{chunk.documentId}, #{chunk.chunkIndex}, #{chunk.content}, #{chunk.embedding}, #{chunk.createdAt})
        </foreach>
        </script>
    """)
    void insertBatch(@Param("chunks") List<KnowledgeChunk> chunks);
    
    /**
     * 根据文档 ID 查询所有片段
     */
    @Select("SELECT * FROM knowledge_chunk WHERE document_id = #{documentId} ORDER BY chunk_index")
    List<KnowledgeChunk> findByDocumentId(@Param("documentId") Long documentId);
    
    /**
     * 查询用户所有文档的片段（用于检索）
     */
    @Select("""
        SELECT kc.* FROM knowledge_chunk kc
        INNER JOIN knowledge_document kd ON kc.document_id = kd.id
        WHERE kd.user_id = #{userId}
    """)
    List<KnowledgeChunk> findByUserId(@Param("userId") String userId);
    
    /**
     * 删除文档的所有片段
     */
    @Delete("DELETE FROM knowledge_chunk WHERE document_id = #{documentId}")
    void deleteByDocumentId(@Param("documentId") Long documentId);
    
    /**
     * 统计文档片段数量
     */
    @Select("SELECT COUNT(*) FROM knowledge_chunk WHERE document_id = #{documentId}")
    int countByDocumentId(@Param("documentId") Long documentId);
}
