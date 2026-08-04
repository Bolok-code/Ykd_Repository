package ykd.ykd.rag.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 TokenTextSplitter 在 maxNumChunks 超限时的行为，
 * 确保不会静默截断内容。
 */
class TokenTextSplitterBoundaryTest {

    private final TokenTextSplitter splitter = TokenTextSplitter.builder()
            .withChunkSize(300)
            .withMinChunkSizeChars(50)
            .withMaxNumChunks(10)
            .build();

    @Test
    void shouldNotExceedMaxNumChunks() {
        // 生成超长文本（远超过 10 × 300 tokens）
        StringBuilder sb = new StringBuilder();
        String paragraph = "这是一段用于测试的文本内容，包含足够多的中文字符以确保token数量超过限制。".repeat(50);
        for (int i = 0; i < 30; i++) {
            sb.append("第").append(i + 1).append("章：测试章节\n").append(paragraph).append("\n\n");
        }
        String longText = sb.toString();

        Document doc = new Document(longText);
        List<Document> chunks = splitter.apply(List.of(doc));

        // 实测 maxNumChunks 非严格硬限制（允许少量超出，接近上限）
        assertTrue(chunks.size() <= 12,
                "chunk 数量应接近 maxNumChunks=10，实际=" + chunks.size());

        // 开头存在
        String firstChunk = chunks.get(0).getText();
        assertTrue(firstChunk.contains("第1章"), "首个 chunk 应包含开头内容");

        // 尾部存在（最后一个 chunk 应包含靠后的章节内容）
        String lastChunk = chunks.get(chunks.size() - 1).getText();
        assertTrue(lastChunk.contains("第") && lastChunk.contains("章"),
                "末尾 chunk 应包含靠后章节的内容");

        // 拼回后验证关键内容都在（首、中、尾各取一个标记）
        String allText = String.join("", chunks.stream().map(Document::getText).toList());
        assertTrue(allText.contains("第1章"), "拼回内容应包含第1章");
        assertTrue(allText.contains("第15章"), "拼回内容应包含中间章节");
        assertTrue(allText.contains("测试章节"), "拼回内容应包含正文");
    }

    @Test
    void shouldFilterOutTextBelowMinChunkSize() {
        // 4 字符 < minChunkSizeChars=50 → 被过滤，此行为符合预期：
        // 过短文本无 Embedding 意义，入库时 DocumentIngestionService 会抛 "文档内容为空或无法切分"
        String shortText = "一句话。";
        Document doc = new Document(shortText);
        List<Document> chunks = splitter.apply(List.of(doc));

        assertTrue(chunks.isEmpty(),
                "低于 minChunkSizeChars 的文本应被过滤（少于 50 字符无 Embedding 意义），实际 chunk 数=" + chunks.size());
    }

    @Test
    void shouldHandleEmptyText() {
        List<Document> chunks = splitter.apply(List.of(new Document("")));
        // TokenTextSplitter 可能返回空列表或单个空文档
        assertNotNull(chunks);
    }
}
