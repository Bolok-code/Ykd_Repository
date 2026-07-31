package ykd.ykd.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ykd.ykd.rag.mapper.KnowledgeChunkMapper;
import ykd.ykd.rag.mapper.KnowledgeDocumentMapper;
import ykd.ykd.rag.model.KnowledgeDocument;

import java.util.List;

/**
 * 知识库管理服务
 * 负责文档的查询、删除等管理操作
 */
@Slf4j
@Service
public class KnowledgeBaseService {
    
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final DocumentIngestionService ingestionService;
    
    public KnowledgeBaseService(
            KnowledgeDocumentMapper documentMapper,
            KnowledgeChunkMapper chunkMapper,
            DocumentIngestionService ingestionService) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.ingestionService = ingestionService;
    }
    
    /**
     * 获取用户的所有文档列表
     * 
     * @param userId 用户ID
     * @return 文档列表（按创建时间降序）
     */
    public List<KnowledgeDocument> listDocuments(String userId) {
        log.info("[KnowledgeBase] 查询文档列表: userId={}", userId);
        
        List<KnowledgeDocument> documents = documentMapper.findByUserId(userId);
        
        log.info("[KnowledgeBase] 找到 {} 个文档", documents.size());
        
        return documents;
    }
    
    /**
     * 获取文档详情（包含片段数量）
     * 
     * @param userId 用户ID
     * @param documentId 文档ID
     * @return 文档详情，如果不存在或不属于该用户则返回 null
     */
    public KnowledgeDocument getDocument(String userId, Long documentId) {
        log.info("[KnowledgeBase] 查询文档详情: userId={}, documentId={}", userId, documentId);
        
        KnowledgeDocument document = documentMapper.findById(documentId);
        
        if (document == null) {
            log.warn("[KnowledgeBase] 文档不存在: documentId={}", documentId);
            return null;
        }
        
        // 验证文档属于该用户
        if (!document.getUserId().equals(userId)) {
            log.warn("[KnowledgeBase] 文档不属于该用户: documentId={}, userId={}, ownerId={}", 
                    documentId, userId, document.getUserId());
            return null;
        }
        
        return document;
    }
    
    /**
     * 获取文档的片段数量
     * 
     * @param documentId 文档ID
     * @return 片段数量
     */
    public int getChunkCount(Long documentId) {
        return chunkMapper.countByDocumentId(documentId);
    }
    
    /**
     * 删除文档
     * 
     * @param userId 用户ID
     * @param documentId 文档ID
     * @return 是否删除成功
     */
    public boolean deleteDocument(String userId, Long documentId) {
        log.info("[KnowledgeBase] 删除文档: userId={}, documentId={}", userId, documentId);
        
        // 验证文档存在且属于该用户
        KnowledgeDocument document = getDocument(userId, documentId);
        if (document == null) {
            log.warn("[KnowledgeBase] 文档不存在或无权限删除: documentId={}, userId={}", 
                    documentId, userId);
            return false;
        }
        
        try {
            ingestionService.deleteDocument(documentId);
            log.info("[KnowledgeBase] 文档删除成功: documentId={}, fileName={}", 
                    documentId, document.getFileName());
            return true;
            
        } catch (Exception e) {
            log.error("[KnowledgeBase] 文档删除失败: documentId={}, error={}", 
                    documentId, e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 批量删除文档
     * 
     * @param userId 用户ID
     * @param documentIds 文档ID列表
     * @return 成功删除的数量
     */
    public int deleteDocuments(String userId, List<Long> documentIds) {
        log.info("[KnowledgeBase] 批量删除文档: userId={}, count={}", userId, documentIds.size());
        
        int deletedCount = 0;
        
        for (Long documentId : documentIds) {
            if (deleteDocument(userId, documentId)) {
                deletedCount++;
            }
        }
        
        log.info("[KnowledgeBase] 批量删除完成: total={}, deleted={}", 
                documentIds.size(), deletedCount);
        
        return deletedCount;
    }
    
    /**
     * 统计用户文档数量
     * 
     * @param userId 用户ID
     * @return 文档总数
     */
    public int countDocuments(String userId) {
        int count = documentMapper.countByUserId(userId);
        log.debug("[KnowledgeBase] 文档统计: userId={}, count={}", userId, count);
        return count;
    }
    
    /**
     * 检查用户是否有知识库文档
     * 
     * @param userId 用户ID
     * @return 是否有文档
     */
    public boolean hasDocuments(String userId) {
        return countDocuments(userId) > 0;
    }
    
    /**
     * 格式化文档列表为可读字符串
     * 
     * @param documents 文档列表
     * @return 格式化的字符串
     */
    public String formatDocumentList(List<KnowledgeDocument> documents) {
        if (documents.isEmpty()) {
            return "📚 知识库为空，还没有添加任何文档。";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("📚 知识库文档列表（共 ").append(documents.size()).append(" 个）：\n\n");
        
        for (int i = 0; i < documents.size(); i++) {
            KnowledgeDocument doc = documents.get(i);
            int chunkCount = getChunkCount(doc.getId());
            
            sb.append(String.format("%d. 【%s】%s\n", 
                    i + 1, 
                    doc.getFileType(), 
                    doc.getFileName()));
            
            sb.append(String.format("   ID: %d | 片段: %d | 状态: %s\n", 
                    doc.getId(), 
                    chunkCount, 
                    getStatusText(doc.getStatus())));
            
            sb.append(String.format("   创建时间: %s\n\n", 
                    doc.getCreatedAt()));
        }
        
        return sb.toString();
    }
    
    /**
     * 将状态码转换为可读文本
     */
    private String getStatusText(String status) {
        return switch (status) {
            case "pending" -> "待处理";
            case "processing" -> "处理中";
            case "completed" -> "已完成";
            case "failed" -> "失败";
            default -> status;
        };
    }
    
    /**
     * 格式化文档详情
     * 
     * @param document 文档
     * @return 格式化的字符串
     */
    public String formatDocumentDetail(KnowledgeDocument document) {
        if (document == null) {
            return "❌ 文档不存在或无权访问";
        }
        
        int chunkCount = getChunkCount(document.getId());
        
        StringBuilder sb = new StringBuilder();
        sb.append("📄 文档详情\n\n");
        sb.append("文件名：").append(document.getFileName()).append("\n");
        sb.append("文件类型：").append(document.getFileType()).append("\n");
        sb.append("文档 ID：").append(document.getId()).append("\n");
        sb.append("片段数量：").append(chunkCount).append("\n");
        sb.append("状态：").append(getStatusText(document.getStatus())).append("\n");
        sb.append("创建时间：").append(document.getCreatedAt()).append("\n");
        sb.append("更新时间：").append(document.getUpdatedAt()).append("\n");
        
        return sb.toString();
    }
}
