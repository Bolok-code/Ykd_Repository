package ykd.ykd.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ykd.ykd.rag.config.RagProperties;
import ykd.ykd.rag.mapper.KnowledgeChunkMapper;
import ykd.ykd.rag.mapper.KnowledgeDocumentMapper;
import ykd.ykd.rag.model.KnowledgeChunk;
import ykd.ykd.rag.model.KnowledgeDocument;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档入库服务
 * 负责将文档解析、切分、向量化并保存到知识库
 */
@Slf4j
@Service
public class DocumentIngestionService {
    
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final TextChunkService textChunkService;
    private final EmbeddingService embeddingService;
    private final RagProperties ragProperties;
    
    public DocumentIngestionService(
            KnowledgeDocumentMapper documentMapper,
            KnowledgeChunkMapper chunkMapper,
            TextChunkService textChunkService,
            EmbeddingService embeddingService,
            RagProperties ragProperties) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.textChunkService = textChunkService;
        this.embeddingService = embeddingService;
        this.ragProperties = ragProperties;
    }
    
    /**
     * 将文档加入知识库
     * 
     * @param userId 用户ID
     * @param fileName 文件名
     * @param fileType 文件类型
     * @param content 文档内容
     * @return 文档ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long ingestDocument(String userId, String fileName, String fileType, String content) {
        log.info("[DocIngestion] 开始入库: userId={}, fileName={}, contentLength={}", 
                userId, fileName, content.length());
        
        // 1. 检查 RAG 功能是否启用
        if (!ragProperties.isEnabled()) {
            log.warn("[DocIngestion] RAG 功能未启用");
            throw new IllegalStateException("RAG 功能未启用");
        }
        
        // 2. 计算文件哈希值，防止重复上传
        String fileHash = calculateHash(content);
        KnowledgeDocument existing = documentMapper.findByUserIdAndFileHash(userId, fileHash);
        if (existing != null) {
            log.info("[DocIngestion] 文档已存在: documentId={}, fileName={}", 
                    existing.getId(), existing.getFileName());
            return existing.getId();
        }
        
        // 3. 创建文档记录
        KnowledgeDocument document = KnowledgeDocument.builder()
                .userId(userId)
                .fileName(fileName)
                .fileType(fileType)
                .fileHash(fileHash)
                .status("processing")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        
        try {
            documentMapper.insert(document);
            Long documentId = document.getId();
            log.info("[DocIngestion] 文档记录已创建: documentId={}", documentId);
            
            // 4. 文本切分
            List<String> chunks = textChunkService.splitText(content);
            if (chunks.isEmpty()) {
                log.warn("[DocIngestion] 文本切分结果为空");
                updateDocumentStatus(documentId, "failed");
                throw new IllegalArgumentException("文档内容为空或无法切分");
            }
            
            log.info("[DocIngestion] 文本切分完成: chunkCount={}", chunks.size());
            
            // 5. 批量生成向量
            List<String> embeddings = embeddingService.embedBatch(chunks);
            if (embeddings.size() != chunks.size()) {
                log.error("[DocIngestion] 向量数量与片段数量不匹配: {} vs {}", 
                        embeddings.size(), chunks.size());
                updateDocumentStatus(documentId, "failed");
                throw new IllegalStateException("向量化失败");
            }
            
            log.info("[DocIngestion] 向量化完成: embeddingCount={}", embeddings.size());
            
            // 6. 创建片段记录
            List<KnowledgeChunk> knowledgeChunks = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                KnowledgeChunk chunk = KnowledgeChunk.builder()
                        .documentId(documentId)
                        .chunkIndex(i)
                        .content(chunks.get(i))
                        .embedding(embeddings.get(i))
                        .createdAt(LocalDateTime.now())
                        .build();
                knowledgeChunks.add(chunk);
            }
            
            // 7. 批量插入片段
            chunkMapper.insertBatch(knowledgeChunks);
            log.info("[DocIngestion] 片段已保存: count={}", knowledgeChunks.size());
            
            // 8. 更新文档状态为完成
            updateDocumentStatus(documentId, "completed");
            
            log.info("[DocIngestion] 文档入库成功: documentId={}, chunkCount={}", 
                    documentId, chunks.size());
            
            return documentId;
            
        } catch (Exception e) {
            log.error("[DocIngestion] 文档入库失败: userId={}, fileName={}, error={}", 
                    userId, fileName, e.getMessage(), e);
            if (document.getId() != null) {
                updateDocumentStatus(document.getId(), "failed");
            }
            throw new RuntimeException("文档入库失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 更新文档状态
     */
    private void updateDocumentStatus(Long documentId, String status) {
        try {
            documentMapper.updateStatus(documentId, status, LocalDateTime.now().toString());
            log.debug("[DocIngestion] 文档状态已更新: documentId={}, status={}", documentId, status);
        } catch (Exception e) {
            log.error("[DocIngestion] 更新文档状态失败: documentId={}, status={}, error={}", 
                    documentId, status, e.getMessage());
        }
    }
    
    /**
     * 计算文本的 SHA-256 哈希值
     */
    private String calculateHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            
            // 转换为十六进制字符串
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
            
        } catch (Exception e) {
            log.error("[DocIngestion] 计算哈希值失败: {}", e.getMessage());
            // 如果哈希计算失败，返回内容长度作为简单标识
            return "fallback_" + content.length() + "_" + System.currentTimeMillis();
        }
    }
    
    /**
     * 删除文档及其所有片段
     * 
     * @param documentId 文档ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocument(Long documentId) {
        log.info("[DocIngestion] 开始删除文档: documentId={}", documentId);
        
        try {
            // 1. 删除所有片段（由于外键约束，实际上可以自动级联删除）
            chunkMapper.deleteByDocumentId(documentId);
            log.info("[DocIngestion] 文档片段已删除: documentId={}", documentId);
            
            // 2. 删除文档记录
            documentMapper.deleteById(documentId);
            log.info("[DocIngestion] 文档记录已删除: documentId={}", documentId);
            
        } catch (Exception e) {
            log.error("[DocIngestion] 删除文档失败: documentId={}, error={}", 
                    documentId, e.getMessage(), e);
            throw new RuntimeException("删除文档失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 估算文档处理后的片段数量
     * 
     * @param content 文档内容
     * @return 预计片段数量
     */
    public int estimateChunkCount(String content) {
        return textChunkService.estimateChunkCount(content);
    }
}
