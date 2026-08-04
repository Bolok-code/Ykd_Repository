package ykd.ykd.rag.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ykd.ykd.llm.tools.DocumentTools;
import ykd.ykd.processor.UserContext;
import ykd.ykd.rag.service.DocumentIngestionService;
import ykd.ykd.rag.service.KnowledgeBaseService;
import ykd.ykd.rag.service.RagRetrievalService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeBaseTools 行为测试：文档入库、知识库问答、列表。
 */
class KnowledgeBaseToolsTest {

    private final DocumentIngestionService ingestionService = mock(DocumentIngestionService.class);
    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final RagRetrievalService retrievalService = mock(RagRetrievalService.class);
    private final UserContext userContext = mock(UserContext.class);

    private KnowledgeBaseTools tools() {
        return new KnowledgeBaseTools(ingestionService, knowledgeBaseService, retrievalService, userContext);
    }

    @AfterEach
    void clearCache() {
        DocumentTools.clearCachedDocument("u1");
    }

    @Test
    void addWithoutCachedDocumentReturnsError() {
        when(userContext.getCurrentUserId()).thenReturn("u1");
        String result = tools().addDocumentToKnowledgeBase();
        assertThat(result).contains("❌");
    }

    @Test
    void addWithCachedPdfIngestsWithRealFileType() {
        DocumentTools.cacheDocument("u1", "report.pdf", "内容");
        when(userContext.getCurrentUserId()).thenReturn("u1");
        when(ingestionService.estimateChunkCount("内容")).thenReturn(3);
        when(ingestionService.ingestDocument("u1", "report.pdf", "PDF", "内容")).thenReturn(42L);

        String result = tools().addDocumentToKnowledgeBase();

        assertThat(result).contains("✅");
        assertThat(result).contains("report.pdf");
        // 以真实文件类型入库，而非硬编码"文档"
        verify(ingestionService).ingestDocument("u1", "report.pdf", "PDF", "内容");
    }

    @Test
    void answerWhenNoDocumentsReturnsHint() {
        when(userContext.getCurrentUserId()).thenReturn("u1");
        when(knowledgeBaseService.hasDocuments("u1")).thenReturn(false);

        String result = tools().answerFromKnowledgeBase("总结一下");

        assertThat(result).contains("知识库");
    }

    @Test
    void listDelegatesToService() {
        when(userContext.getCurrentUserId()).thenReturn("u1");
        when(knowledgeBaseService.listDocuments("u1")).thenReturn(List.of());
        when(knowledgeBaseService.formatDocumentList(List.of())).thenReturn("（暂无文档）");

        String result = tools().listKnowledgeBase();

        assertThat(result).isEqualTo("（暂无文档）");
    }
}
