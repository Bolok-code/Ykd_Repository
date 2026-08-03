package ykd.ykd.rag.mapper;

import org.apache.ibatis.annotations.*;
import ykd.ykd.rag.model.KnowledgeDocument;

import java.util.List;

/**
 * 知识库文档 Mapper
 */
@Mapper
public interface KnowledgeDocumentMapper {
    
    /**
     * 插入文档记录
     */
    @Insert("""
        INSERT INTO knowledge_document 
        (user_id, file_name, file_type, file_hash, status, created_at, updated_at)
        VALUES (#{userId}, #{fileName}, #{fileType}, #{fileHash}, #{status}, #{createdAt}, #{updatedAt})
    """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(KnowledgeDocument document);
    
    /**
     * 根据 ID 查询文档
     */
    @Select("SELECT * FROM knowledge_document WHERE id = #{id}")
    KnowledgeDocument findById(@Param("id") Long id);

    /**
     * 根据 ID 列表批量查询文档
     */
    @Select("""
        <script>
        SELECT * FROM knowledge_document WHERE id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">
            #{id}
        </foreach>
        </script>
    """)
    List<KnowledgeDocument> findByIds(@Param("ids") List<Long> ids);
    
    /**
     * 根据用户 ID 查询所有文档
     */
    @Select("SELECT * FROM knowledge_document WHERE user_id = #{userId} ORDER BY created_at DESC")
    List<KnowledgeDocument> findByUserId(@Param("userId") String userId);
    
    /**
     * 根据用户 ID 和文件哈希查询文档（防重复）
     */
    @Select("SELECT * FROM knowledge_document WHERE user_id = #{userId} AND file_hash = #{fileHash}")
    KnowledgeDocument findByUserIdAndFileHash(@Param("userId") String userId, @Param("fileHash") String fileHash);
    
    /**
     * 更新文档状态
     */
    @Update("UPDATE knowledge_document SET status = #{status}, updated_at = #{updatedAt} WHERE id = #{id}")
    void updateStatus(@Param("id") Long id, @Param("status") String status, @Param("updatedAt") String updatedAt);
    
    /**
     * 删除文档
     */
    @Delete("DELETE FROM knowledge_document WHERE id = #{id}")
    void deleteById(@Param("id") Long id);
    
    /**
     * 统计用户文档数量
     */
    @Select("SELECT COUNT(*) FROM knowledge_document WHERE user_id = #{userId}")
    int countByUserId(@Param("userId") String userId);
}
