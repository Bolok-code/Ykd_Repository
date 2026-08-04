package ykd.ykd.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ykd.ykd.rag.config.RagProperties;
import ykd.ykd.rag.mapper.KnowledgeChunkMapper;
import ykd.ykd.rag.mapper.KnowledgeDocumentMapper;
import ykd.ykd.rag.model.KnowledgeChunk;
import ykd.ykd.rag.model.KnowledgeDocument;
import ykd.ykd.rag.model.RagSearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 检索服务
 * 负责根据用户问题检索最相关的文档片段
 */
@Slf4j
@Service
public class RagRetrievalService {
    
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final EmbeddingService embeddingService;
    private final RagProperties ragProperties;
    
    public RagRetrievalService(
            KnowledgeChunkMapper chunkMapper,
            KnowledgeDocumentMapper documentMapper,
            EmbeddingService embeddingService,
            RagProperties ragProperties) {
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
        this.embeddingService = embeddingService;
        this.ragProperties = ragProperties;
    }
    
    /**
     * 根据问题检索相关文档片段
     * 
     * @param userId 用户ID
     * @param question 用户问题
     * @return 最相关的文档片段列表（按相似度降序排列）
     */
    public List<RagSearchResult> retrieve(String userId, String question) {
        log.info("[RagRetrieval] 开始检索: userId={}, question={}", userId, question);
        
        // 1. 检查 RAG 功能是否启用
        if (!ragProperties.isEnabled()) {
            log.warn("[RagRetrieval] RAG 功能未启用");
            return List.of();
        }
        
        // 2. 将问题转换为向量
        String questionEmbedding = embeddingService.embed(question);
        float[] questionVector = embeddingService.jsonToFloatArray(questionEmbedding);
        
        if (questionVector.length == 0) {
            log.error("[RagRetrieval] 问题向量化失败");
            return List.of();
        }
        
        log.debug("[RagRetrieval] 问题向量化完成: dim={}", questionVector.length);
        
        // 3. 获取用户的所有文档片段
        List<KnowledgeChunk> allChunks = chunkMapper.findByUserId(userId);
        
        if (allChunks.isEmpty()) {
            log.info("[RagRetrieval] 用户没有知识库文档: userId={}", userId);
            return List.of();
        }
        
        log.info("[RagRetrieval] 找到 {} 个文档片段", allChunks.size());
        
        // 4. 获取文档信息（用于显示来源文件名）
        Map<Long, String> documentNames = getDocumentNames(allChunks);
        
        // 5. 计算每个片段与问题的相似度
        List<RagSearchResult> results = new ArrayList<>();
        
        for (KnowledgeChunk chunk : allChunks) {
            float[] chunkVector = embeddingService.jsonToFloatArray(chunk.getEmbedding());
            
            if (chunkVector.length == 0) {
                log.warn("[RagRetrieval] 片段向量为空: chunkId={}", chunk.getId());
                continue;
            }
            
            double similarity = embeddingService.cosineSimilarity(questionVector, chunkVector);
            
            // 过滤低于阈值的结果
            if (similarity < ragProperties.getSimilarityThreshold()) {
                continue;
            }
            
            RagSearchResult result = RagSearchResult.builder()
                    .documentId(chunk.getDocumentId())
                    .fileName(documentNames.get(chunk.getDocumentId()))
                    .content(chunk.getContent())
                    .score(similarity)
                    .chunkIndex(chunk.getChunkIndex())
                    .build();
            
            results.add(result);
        }
        
        // 6. 按相似度降序排序，取 Top-K
        List<RagSearchResult> topResults = results.stream()
                .sorted(Comparator.comparingDouble(RagSearchResult::getScore).reversed())
                .limit(ragProperties.getTopK())
                .toList();
        
        log.info("[RagRetrieval] 检索完成: totalChunks={}, matchedChunks={}, topK={}",
                allChunks.size(), results.size(), topResults.size());

        // 打印 Top-5 分数分布，便于观察阈值是否合理
        if (!topResults.isEmpty()) {
            StringBuilder scoreLog = new StringBuilder("[RagRetrieval] Top-5 分数: ");
            int limit = Math.min(5, topResults.size());
            for (int i = 0; i < limit; i++) {
                RagSearchResult r = topResults.get(i);
                scoreLog.append(String.format("[%d] %.3f (%s)", i + 1, r.getScore(), r.getFileName()));
                if (i < limit - 1) scoreLog.append(", ");
            }
            log.info(scoreLog.toString());
        }

        return topResults;
    }
    
    /**
     * 获取文档 ID 到文件名的映射
     */
    private Map<Long, String> getDocumentNames(List<KnowledgeChunk> chunks) {
        List<Long> documentIds = chunks.stream()
                .map(KnowledgeChunk::getDocumentId)
                .distinct()
                .toList();

        return documentMapper.findByIds(documentIds).stream()
                .collect(Collectors.toMap(
                        KnowledgeDocument::getId,
                        KnowledgeDocument::getFileName
                ));
    }
    
    /**
     * 将检索结果格式化为上下文字符串
     * 用于传递给 LLM 生成回答
     * 
     * @param results 检索结果
     * @return 格式化的上下文
     */
    public String formatContext(List<RagSearchResult> results) {
        if (results.isEmpty()) {
            return "";
        }
        
        StringBuilder context = new StringBuilder();
        context.append("📚 以下是从知识库中检索到的相关内容：\n\n");
        
        int totalChars = 0;
        int maxContextChars = ragProperties.getMaxContextChars();
        
        for (int i = 0; i < results.size(); i++) {
            RagSearchResult result = results.get(i);
            
            String snippet = String.format(
                    "【片段 %d】来源：%s（相似度：%.2f）\n%s\n\n",
                    i + 1,
                    result.getFileName(),
                    result.getScore(),
                    result.getContent()
            );
            
            // 检查是否超过最大字符数限制
            if (totalChars + snippet.length() > maxContextChars) {
                log.info("[RagRetrieval] 上下文已达上限: {}字，停止添加更多片段", totalChars);
                break;
            }
            
            context.append(snippet);
            totalChars += snippet.length();
        }
        
        context.append("---\n");
        context.append("请仅根据以上检索到的内容回答用户的问题。");
        context.append("如果检索到的内容不足以回答问题，请明确告知用户。");
        context.append("回答时请在末尾标注引用的来源文件名。");
        
        log.debug("[RagRetrieval] 上下文格式化完成: totalChars={}, snippetCount={}", 
                totalChars, results.size());
        
        return context.toString();
    }
    
    /**
     * 检索并格式化上下文（一步完成）
     * 
     * @param userId 用户ID
     * @param question 用户问题
     * @return 格式化的上下文
     */
    public String retrieveAndFormatContext(String userId, String question) {
        List<RagSearchResult> results = retrieve(userId, question);
        return formatContext(results);
    }
}
