package ykd.ykd.llm.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocumentTools 缓存行为测试：容量上限、TTL、字节预算逐出。
 */
class DocumentToolsTest {

    @AfterEach
    void clearAll() {
        for (String user : new String[]{"u1", "u2", "u3"}) {
            DocumentTools.clearCachedDocument(user);
        }
    }

    @Test
    void shouldCacheAndReadDocument() {
        new DocumentTools(null, null, 10, 60_000, 1024 * 1024);

        DocumentTools.cacheDocument("u1", "a.txt", "hello world");

        assertThat(DocumentTools.hasCachedDocument("u1")).isTrue();
        assertThat(DocumentTools.getCachedFileName("u1")).isEqualTo("a.txt");
        assertThat(DocumentTools.getCachedContent("u1")).isEqualTo("hello world");
    }

    @Test
    void shouldExpireByTtl() {
        new DocumentTools(null, null, 10, 1, 1024 * 1024); // TTL=1ms

        DocumentTools.cacheDocument("u1", "a.txt", "hello");
        sleep(5);

        assertThat(DocumentTools.hasCachedDocument("u1")).isFalse();
        assertThat(DocumentTools.getCachedContent("u1")).isNull();
        assertThat(DocumentTools.getCachedFileName("u1")).isNull();
    }

    @Test
    void shouldEvictOldestWhenOverEntryLimit() {
        new DocumentTools(null, null, 2, 600_000, 10 * 1024 * 1024);

        DocumentTools.cacheDocument("u1", "a.txt", "a");
        DocumentTools.cacheDocument("u2", "b.txt", "b");
        DocumentTools.cacheDocument("u3", "c.txt", "c"); // 第 3 条，逐出最旧的 u1

        assertThat(DocumentTools.hasCachedDocument("u1")).isFalse();
        assertThat(DocumentTools.hasCachedDocument("u2")).isTrue();
        assertThat(DocumentTools.hasCachedDocument("u3")).isTrue();
    }

    @Test
    void shouldEvictWhenOverByteBudget() {
        // 字节预算极小，单条超预算的条目也会被逐出
        new DocumentTools(null, null, 10, 600_000, 50);

        DocumentTools.cacheDocument("u1", "a.txt", "aaaa");          // 4 字符 ≈ 8 字节
        DocumentTools.cacheDocument("u2", "b.txt", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"); // > 50 字节

        assertThat(DocumentTools.hasCachedDocument("u1")).isFalse();
        assertThat(DocumentTools.hasCachedDocument("u2")).isFalse();
    }

    @Test
    void shouldReturnErrorWhenNoCachedDocument() {
        new DocumentTools(null, null, 10, 60_000, 1024 * 1024);

        assertThat(DocumentTools.hasCachedDocument("nobody")).isFalse();
        assertThat(DocumentTools.getCachedContent("nobody")).isNull();
        assertThat(DocumentTools.getCachedBytes("nobody")).isNull();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
