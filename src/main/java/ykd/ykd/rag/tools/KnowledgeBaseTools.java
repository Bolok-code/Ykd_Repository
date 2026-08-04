package ykd.ykd.rag.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import ykd.ykd.llm.tools.DocumentTools;
import ykd.ykd.processor.UserContext;
import ykd.ykd.rag.model.KnowledgeDocument;
import ykd.ykd.rag.service.DocumentIngestionService;
import ykd.ykd.rag.service.KnowledgeBaseService;
import ykd.ykd.rag.service.RagRetrievalService;

import java.util.List;

/**
 * 知识库工具
 * 供 AI Agent 调用，实现文档管理和知识问答
 */
@Slf4j
@Component
public class KnowledgeBaseTools {
    
    private final DocumentIngestionService ingestionService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final RagRetrievalService retrievalService;
    private final UserContext userContext;
    
    public KnowledgeBaseTools(
            DocumentIngestionService ingestionService,
            KnowledgeBaseService knowledgeBaseService,
            RagRetrievalService retrievalService,
            UserContext userContext) {
        this.ingestionService = ingestionService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.retrievalService = retrievalService;
        this.userContext = userContext;
    }
    
    /**
     * 将文档添加到知识库
     */
    @Tool(description = "将刚才解析的文档添加到个人知识库。用户说'把这份文档加入知识库'、'保存到我的知识库'、'记住这份资料'时调用此工具")
    public String addDocumentToKnowledgeBase() {

        String userId = userContext.getCurrentUserId();
        log.info("[KnowledgeBaseTools] 添加文档到知识库: userId={}", userId);

        // 1. 检查是否有缓存的文档
        if (!DocumentTools.hasCachedDocument(userId)) {
            return "❌ 没有检测到待添加的文档。请先发送文件并让我解析，然后再说'加入知识库'。";
        }

        // 2. 获取缓存的文档信息
        String fileName = DocumentTools.getCachedFileName(userId);
        String content = DocumentTools.getCachedContent(userId);

        if (content == null || content.isBlank()) {
            return "❌ 文档内容为空，无法添加到知识库。";
        }

        try {
            // 3. 估算片段数量
            int estimatedChunks = ingestionService.estimateChunkCount(content);

            log.info("[KnowledgeBaseTools] 开始入库: fileName={}, contentLength={}, estimatedChunks={}",
                    fileName, content.length(), estimatedChunks);

            // 4. 执行入库操作，文件类型根据扩展名推断
            String fileType = detectFileType(fileName);
            Long documentId = ingestionService.ingestDocument(
                    userId,
                    fileName,
                    fileType,
                    content
            );
            
            // 5. 清除缓存（可选，根据需求决定）
            // DocumentTools.clearCachedDocument(userId);
            
            String result = String.format(
                    "✅ 文档已成功添加到知识库！\n\n" +
                    "📄 文件名：%s\n" +
                    "📊 文档 ID：%d\n" +
                    "📝 片段数：%d\n\n" +
                    "现在你可以针对这份文档提问了，比如：\n" +
                    "• 这份文档主要讲什么？\n" +
                    "• 帮我总结文档中的关键信息\n" +
                    "• 文档中提到了哪些技术？",
                    fileName, documentId, estimatedChunks
            );
            
            log.info("[KnowledgeBaseTools] 文档入库成功: documentId={}, chunks={}", 
                    documentId, estimatedChunks);
            
            return result;
            
        } catch (Exception e) {
            log.error("[KnowledgeBaseTools] 文档入库失败: {}", e.getMessage(), e);
            return "❌ 添加文档失败：" + e.getMessage();
        }
    }
    
    /**
     * 基于知识库回答问题
     */
    @Tool(description = "从个人知识库中检索相关内容并回答问题。用户针对已添加的文档提问时调用，如'根据我的简历总结项目经历'、'文档中提到什么技术栈？'")
    public String answerFromKnowledgeBase(
            @ToolParam(description = "用户的问题") String question) {
        
        String userId = userContext.getCurrentUserId();
        log.info("[KnowledgeBaseTools] 知识库问答: userId={}, question={}", userId, question);
        
        // 1. 检查用户是否有知识库文档
        if (!knowledgeBaseService.hasDocuments(userId)) {
            return "📚 你的知识库还是空的。\n\n" +
                   "请先发送 PDF、Word、Excel 等文档，然后说'把这份文档加入知识库'。";
        }
        
        try {
            // 2. 检索相关内容并格式化为上下文
            String context = retrievalService.retrieveAndFormatContext(userId, question);
            
            if (context == null || context.isBlank()) {
                return "📚 知识库中没有找到与你的问题相关的内容。\n\n" +
                       "可能原因：\n" +
                       "• 问题与已添加的文档内容不匹配\n" +
                       "• 文档内容不足以回答该问题\n\n" +
                       "你可以尝试：\n" +
                       "• 查看知识库文档列表\n" +
                       "• 添加更多相关文档";
            }
            
            // 3. 返回检索到的上下文，让 AI 基于此回答
            // 注意：这里返回的内容会被 AI 模型看到，AI 会基于这些内容生成最终回答
            return context;
            
        } catch (Exception e) {
            log.error("[KnowledgeBaseTools] 知识库检索失败: {}", e.getMessage(), e);
            return "❌ 检索知识库失败：" + e.getMessage();
        }
    }
    
    /**
     * 查看知识库文档列表
     */
    @Tool(description = "查看个人知识库中的所有文档。用户说'查看我的知识库'、'我添加了哪些文档'、'知识库列表'时调用")
    public String listKnowledgeBase() {
        
        String userId = userContext.getCurrentUserId();
        log.info("[KnowledgeBaseTools] 查看知识库列表: userId={}", userId);
        
        try {
            List<KnowledgeDocument> documents = knowledgeBaseService.listDocuments(userId);
            return knowledgeBaseService.formatDocumentList(documents);
            
        } catch (Exception e) {
            log.error("[KnowledgeBaseTools] 查询知识库列表失败: {}", e.getMessage(), e);
            return "❌ 查询知识库失败：" + e.getMessage();
        }
    }
    
    /**
     * 查看文档详情
     */
    @Tool(description = "查看知识库中某个文档的详细信息。用户说'查看文档详情'、'文档 X 的信息'时调用")
    public String getDocumentDetail(
            @ToolParam(description = "文档 ID") Long documentId) {
        
        String userId = userContext.getCurrentUserId();
        log.info("[KnowledgeBaseTools] 查看文档详情: userId={}, documentId={}", userId, documentId);
        
        try {
            KnowledgeDocument document = knowledgeBaseService.getDocument(userId, documentId);
            return knowledgeBaseService.formatDocumentDetail(document);
            
        } catch (Exception e) {
            log.error("[KnowledgeBaseTools] 查询文档详情失败: {}", e.getMessage(), e);
            return "❌ 查询文档详情失败：" + e.getMessage();
        }
    }
    
    /**
     * 从知识库删除文档
     */
    @Tool(description = "从知识库中删除指定文档。用户说'删除文档 X'、'从知识库移除文档 Y'时调用")
    public String deleteDocument(
            @ToolParam(description = "要删除的文档 ID") Long documentId) {
        
        String userId = userContext.getCurrentUserId();
        log.info("[KnowledgeBaseTools] 删除文档: userId={}, documentId={}", userId, documentId);
        
        try {
            // 先获取文档信息
            KnowledgeDocument document = knowledgeBaseService.getDocument(userId, documentId);
            
            if (document == null) {
                return "❌ 文档不存在或无权访问";
            }
            
            String fileName = document.getFileName();
            
            // 执行删除
            boolean success = knowledgeBaseService.deleteDocument(userId, documentId);
            
            if (success) {
                return String.format(
                        "✅ 文档已从知识库删除\n\n" +
                        "📄 文件名：%s\n" +
                        "📊 文档 ID：%d",
                        fileName, documentId
                );
            } else {
                return "❌ 删除文档失败";
            }
            
        } catch (Exception e) {
            log.error("[KnowledgeBaseTools] 删除文档失败: {}", e.getMessage(), e);
            return "❌ 删除文档失败：" + e.getMessage();
        }
    }

    /**
     * 根据文件扩展名推断真实文档类型，避免入库时硬编码为"文档"。
     */
    private String detectFileType(String fileName) {
        if (fileName == null) return "文档";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".docx") || lower.endsWith(".doc")) return "Word";
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) return "Excel";
        if (lower.endsWith(".md")) return "Markdown";
        if (lower.endsWith(".txt")) return "文本";
        if (lower.endsWith(".csv")) return "CSV";
        return "文档";
    }
}
