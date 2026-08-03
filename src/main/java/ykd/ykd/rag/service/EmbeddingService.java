package ykd.ykd.rag.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import ykd.ykd.rag.config.RagProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Embedding 向量服务
 * 使用 DashScope Embedding 模型将文本转换为向量表示
 */
@Slf4j
@Service
public class EmbeddingService {

    private static final int DEFAULT_EMBED_BATCH_SIZE = 10;

    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;

    public EmbeddingService(@Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel,
                            RagProperties ragProperties) {
        this.embeddingModel = embeddingModel;
        this.objectMapper = new ObjectMapper();
        this.ragProperties = ragProperties;
    }
    
    /**
     * 将单个文本转换为向量
     * 
     * @param text 输入文本
     * @return 向量的 JSON 字符串表示
     */
    public String embed(String text) {
        if (text == null || text.isBlank()) {
            log.warn("[Embedding] 输入文本为空");
            return "[]";
        }
        
        try {
            EmbeddingResponse response = embeddingModel.embedForResponse(List.of(text));

            if (response == null || response.getResults().isEmpty()) {
                log.error("[Embedding] 模型返回空结果");
                throw new IllegalStateException("Embedding 模型返回空结果");
            }

            float[] vector = response.getResults().get(0).getOutput();

            if (vector == null || vector.length == 0) {
                log.error("[Embedding] 返回的向量为空");
                throw new IllegalStateException("Embedding 返回的向量为空");
            }

            String vectorJson = floatArrayToJson(vector);

            log.debug("[Embedding] 文本向量化完成: textLength={}, vectorDim={}",
                    text.length(), vector.length);

            return vectorJson;

        } catch (Exception e) {
            log.error("[Embedding] 向量化失败: {}", e.getMessage(), e);
            throw new RuntimeException("向量化失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 批量将文本转换为向量，自动按 {@link RagProperties#getEmbedBatchSize()} 分批请求。
     *
     * @param texts 文本列表
     * @return 向量 JSON 字符串列表
     */
    public List<String> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        // 过滤空白文本
        List<String> validTexts = texts.stream()
                .filter(text -> text != null && !text.isBlank())
                .toList();

        if (validTexts.isEmpty()) {
            log.warn("[Embedding] 所有输入文本为空");
            return new ArrayList<>();
        }

        int batchSize = ragProperties.getEmbedBatchSize();
        if (batchSize <= 0) {
            batchSize = DEFAULT_EMBED_BATCH_SIZE;
            log.warn("[Embedding] rag.embed-batch-size 配置无效 ({}), 使用默认值 {}",
                    ragProperties.getEmbedBatchSize(), DEFAULT_EMBED_BATCH_SIZE);
        }

        log.info("[Embedding] 批量向量化开始: totalCount={}, batchSize={}", validTexts.size(), batchSize);

        List<String> allVectors = new ArrayList<>();

        for (int batchStart = 0; batchStart < validTexts.size(); batchStart += batchSize) {
            int batchEnd = Math.min(batchStart + batchSize, validTexts.size());
            List<String> batch = validTexts.subList(batchStart, batchEnd);

            try {
                log.debug("[Embedding] 发送批次: [{}-{})/{}", batchStart, batchEnd, validTexts.size());

                EmbeddingResponse response = embeddingModel.embedForResponse(batch);

                if (response == null || response.getResults().isEmpty()) {
                    log.error("[Embedding] 批次 [{}-{}) 返回空结果", batchStart, batchEnd);
                    throw new IllegalStateException("Embedding 批量向量化返回空结果");
                }

                for (int i = 0; i < response.getResults().size(); i++) {
                    float[] vector = response.getResults().get(i).getOutput();

                    if (vector == null || vector.length == 0) {
                        log.error("[Embedding] 批次中第 {} 个向量为空", batchStart + i);
                        throw new IllegalStateException("Embedding 第 " + (batchStart + i) + " 个向量为空");
                    }

                    allVectors.add(floatArrayToJson(vector));
                }

            } catch (Exception e) {
                log.error("[Embedding] 批次 [{}-{}) 向量化失败: {}", batchStart, batchEnd, e.getMessage(), e);
                throw new RuntimeException("批量向量化失败 (batch [" + batchStart + "-" + batchEnd + ")): " + e.getMessage(), e);
            }
        }

        log.info("[Embedding] 批量向量化完成: totalCount={}, batchCount={}",
                allVectors.size(),
                (int) Math.ceil((double) validTexts.size() / batchSize));

        return allVectors;
    }
    
    /**
     * 将 float 数组转换为 JSON 字符串
     */
    private String floatArrayToJson(float[] array) {
        try {
            return objectMapper.writeValueAsString(array);
        } catch (JsonProcessingException e) {
            log.error("[Embedding] 向量序列化失败: {}", e.getMessage());
            return "[]";
        }
    }
    
    /**
     * 将 JSON 字符串解析为 float 数组
     */
    public float[] jsonToFloatArray(String json) {
        try {
            return objectMapper.readValue(json, float[].class);
        } catch (JsonProcessingException e) {
            log.error("[Embedding] 向量反序列化失败: {}", e.getMessage());
            return new float[0];
        }
    }
    
    /**
     * 计算两个向量的余弦相似度
     * 
     * @param vec1 向量1
     * @param vec2 向量2
     * @return 相似度（0-1之间，1表示完全相同）
     */
    public double cosineSimilarity(float[] vec1, float[] vec2) {
        if (vec1 == null || vec2 == null || vec1.length == 0 || vec2.length == 0) {
            log.warn("[Embedding] 向量为空，无法计算相似度");
            return 0.0;
        }
        
        if (vec1.length != vec2.length) {
            log.warn("[Embedding] 向量维度不匹配: {} vs {}", vec1.length, vec2.length);
            return 0.0;
        }
        
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        
        for (int i = 0; i < vec1.length; i++) {
            dotProduct += vec1[i] * vec2[i];
            norm1 += vec1[i] * vec1[i];
            norm2 += vec2[i] * vec2[i];
        }
        
        if (norm1 == 0.0 || norm2 == 0.0) {
            return 0.0;
        }
        
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
    
    /**
     * 计算两个向量的余弦相似度（JSON 格式输入）
     * 
     * @param json1 向量1的JSON字符串
     * @param json2 向量2的JSON字符串
     * @return 相似度（0-1之间）
     */
    public double cosineSimilarity(String json1, String json2) {
        float[] vec1 = jsonToFloatArray(json1);
        float[] vec2 = jsonToFloatArray(json2);
        return cosineSimilarity(vec1, vec2);
    }
}
