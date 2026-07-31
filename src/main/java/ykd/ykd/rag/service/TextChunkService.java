package ykd.ykd.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ykd.ykd.rag.config.RagProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本切分服务
 * 负责将长文本按固定大小切分成多个片段，相邻片段之间有重叠部分
 */
@Slf4j
@Service
public class TextChunkService {
    
    private final RagProperties ragProperties;
    
    public TextChunkService(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }
    
    /**
     * 将文本切分为多个片段
     * 
     * @param text 待切分的文本
     * @return 文本片段列表
     */
    public List<String> splitText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        
        int chunkSize = ragProperties.getChunkSize();
        int chunkOverlap = ragProperties.getChunkOverlap();
        
        // 确保重叠部分小于片段大小
        if (chunkOverlap >= chunkSize) {
            log.warn("[TextChunk] 重叠大小({})超过片段大小({}), 自动调整为片段大小的一半", 
                    chunkOverlap, chunkSize);
            chunkOverlap = chunkSize / 2;
        }
        
        List<String> chunks = new ArrayList<>();
        int textLength = text.length();
        int start = 0;
        
        while (start < textLength) {
            // 计算当前片段的结束位置
            int end = Math.min(start + chunkSize, textLength);
            
            // 如果不是最后一个片段，尝试在句子边界处切分
            if (end < textLength) {
                end = findSentenceBoundary(text, end);
            }
            
            // 提取片段
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
                log.debug("[TextChunk] 片段 {}: 起始={}, 结束={}, 长度={}", 
                        chunks.size(), start, end, chunk.length());
            }
            
            // 如果已经到达文本末尾，退出循环
            if (end >= textLength) {
                break;
            }
            
            // 计算下一个片段的起始位置（考虑重叠）
            start = end - chunkOverlap;
            
            // 防止无限循环
            if (start <= 0 || start >= textLength) {
                break;
            }
        }
        
        log.info("[TextChunk] 文本切分完成: 原文长度={}, 片段数={}, 片段大小={}, 重叠={}", 
                textLength, chunks.size(), chunkSize, chunkOverlap);
        
        return chunks;
    }
    
    /**
     * 在指定位置附近查找句子边界
     * 优先查找句号、问号、感叹号、换行符等
     * 
     * @param text 文本
     * @param position 期望的切分位置
     * @return 调整后的切分位置
     */
    private int findSentenceBoundary(String text, int position) {
        // 在期望位置前后一定范围内查找句子边界
        int searchRange = 50;
        int start = Math.max(position - searchRange, 0);
        int end = Math.min(position + searchRange, text.length());
        
        // 句子结束标记（按优先级排序）
        char[] sentenceEndings = {'\n', '。', '！', '？', '.', '!', '?', '；', ';', '，', ','};
        
        // 从期望位置向后查找
        for (int i = position; i < end; i++) {
            char c = text.charAt(i);
            for (char ending : sentenceEndings) {
                if (c == ending) {
                    // 返回标点符号之后的位置
                    return Math.min(i + 1, text.length());
                }
            }
        }
        
        // 从期望位置向前查找
        for (int i = position - 1; i >= start; i--) {
            char c = text.charAt(i);
            for (char ending : sentenceEndings) {
                if (c == ending) {
                    // 返回标点符号之后的位置
                    return Math.min(i + 1, text.length());
                }
            }
        }
        
        // 如果没有找到句子边界，尝试在空格处切分
        for (int i = position; i < end; i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        
        for (int i = position - 1; i >= start; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        
        // 如果都没找到，返回原始位置
        return position;
    }
    
    /**
     * 计算切分后的片段数量（不实际切分）
     * 
     * @param text 文本
     * @return 预计的片段数量
     */
    public int estimateChunkCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        
        int chunkSize = ragProperties.getChunkSize();
        int chunkOverlap = ragProperties.getChunkOverlap();
        int textLength = text.length();
        
        if (textLength <= chunkSize) {
            return 1;
        }
        
        // 估算公式：(文本长度 - 片段大小) / (片段大小 - 重叠大小) + 1
        int effectiveChunkSize = chunkSize - chunkOverlap;
        return (int) Math.ceil((double) (textLength - chunkSize) / effectiveChunkSize) + 1;
    }
}
