package ykd.ykd.rag.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ykd.ykd.document.DocumentParsingService;
import ykd.ykd.document.ParseResult;
import ykd.ykd.rag.config.RagProperties;
import ykd.ykd.rag.mapper.KnowledgeDocumentMapper;
import ykd.ykd.rag.model.KnowledgeDocument;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeBaseAutoIngestionService 行为测试：
 * 入库成功移动、哈希去重、失败文件移走避免无限重试。
 */
class KnowledgeBaseAutoIngestionServiceTest {

    @TempDir
    Path tempDir;

    private final DocumentParsingService parsingService = mock(DocumentParsingService.class);
    private final DocumentIngestionService ingestionService = mock(DocumentIngestionService.class);
    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);

    private RagProperties enabledProps() {
        RagProperties props = new RagProperties();
        props.setEnabled(true);
        props.setAutoIngestEnabled(true);
        props.setAutoIngestDir(tempDir.toString());
        return props;
    }

    private KnowledgeBaseAutoIngestionService service(RagProperties props) {
        return new KnowledgeBaseAutoIngestionService(
                props, parsingService, ingestionService, knowledgeBaseService, documentMapper);
    }

    @Test
    void shouldSkipWhenAutoIngestDisabled() throws Exception {
        RagProperties props = new RagProperties();
        props.setAutoIngestEnabled(false);

        service(props).run(null);

        verify(parsingService, never()).parse(any(Path.class), anyString());
    }

    @Test
    void shouldMoveFileToDoneAfterSuccessfulIngest() throws Exception {
        Path file = tempDir.resolve("doc.pdf");
        Files.writeString(file, "pdf content");
        when(parsingService.parse(eq(file), anyString()))
                .thenReturn(new ParseResult("PDF", 1, "内容"));
        when(documentMapper.findByUserIdAndFileHash(eq("system"), anyString())).thenReturn(null);
        when(ingestionService.ingestDocument(eq("system"), eq("doc.pdf"), eq("PDF"), anyString()))
                .thenReturn(42L);

        service(enabledProps()).run(null);

        assertThat(tempDir.resolve(".ingested").resolve("doc.pdf")).exists();
        assertThat(file).doesNotExist();
    }

    @Test
    void shouldSkipAndMoveWhenHashAlreadyExists() throws Exception {
        Path file = tempDir.resolve("doc.pdf");
        Files.writeString(file, "pdf content");
        when(parsingService.parse(eq(file), anyString()))
                .thenReturn(new ParseResult("PDF", 1, "内容"));
        when(documentMapper.findByUserIdAndFileHash(eq("system"), anyString()))
                .thenReturn(KnowledgeDocument.builder().id(1L).fileName("doc.pdf").build());

        service(enabledProps()).run(null);

        verify(ingestionService, never()).ingestDocument(anyString(), anyString(), anyString(), anyString());
        assertThat(tempDir.resolve(".ingested").resolve("doc.pdf")).exists();
    }

    @Test
    void shouldMoveFailedFileAwayToAvoidInfiniteRetry() throws Exception {
        Path file = tempDir.resolve("broken.pdf");
        Files.writeString(file, "broken");
        when(parsingService.parse(eq(file), anyString()))
                .thenThrow(new RuntimeException("parse failed"));

        service(enabledProps()).run(null);

        // 失败的文件也被移走，下次启动不会对同一损坏文件无限重试
        assertThat(tempDir.resolve(".ingested").resolve("broken.pdf")).exists();
        assertThat(file).doesNotExist();
    }
}
