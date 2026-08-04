package ykd.ykd.llm.tools;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.CDNMedia;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ykd.ykd.document.ParseResult;
import ykd.ykd.document.DocumentParsingService;
import ykd.ykd.processor.UserContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 文档解析工具，供 LLM 通过 {@code parseDocument} 读取用户发送的文件。
 *
 * <p>文件内容按 userId 缓存在内存中，缓存带 <b>容量上限 / TTL / 字节预算</b>，
 * 避免用户反复发送大文件（如 5MB PDF）导致堆内存不释放。</p>
 */
@Slf4j
@Component
public class DocumentTools {

    private final DocumentParsingService documentParsingService;
    private final UserContext userContext;

    // ── 缓存配置（可在 application.yml / application-local.yml 覆盖） ──
    /** 最多同时缓存多少个用户的文档 */
    private static int maxCacheEntries = 20;
    /** 单条目缓存有效时长（毫秒） */
    private static long cacheTtlMs = 10 * 60 * 1000L;
    /** 全部条目累计字节预算（约 50MB），超过则逐出最旧条目 */
    private static long maxCacheBytes = 50L * 1024 * 1024;

    /** userId → 解析后的文档（带时间戳） */
    private static final Map<String, ParsedDocument> userDocumentCache = new ConcurrentHashMap<>();
    private static final AtomicLong totalCachedBytes = new AtomicLong();

    public DocumentTools(DocumentParsingService documentParsingService,
                         UserContext userContext,
                         @Value("${document.cache.max-entries:20}") int maxCacheEntries,
                         @Value("${document.cache.ttl-ms:600000}") long cacheTtlMs,
                         @Value("${document.cache.max-bytes:52428800}") long maxCacheBytes) {
        this.documentParsingService = documentParsingService;
        this.userContext = userContext;
        DocumentTools.maxCacheEntries = Math.max(1, maxCacheEntries);
        DocumentTools.cacheTtlMs = Math.max(1L, cacheTtlMs);
        DocumentTools.maxCacheBytes = Math.max(1L, maxCacheBytes);
    }

    public static boolean hasCachedDocument(String userId) {
        evictExpired();
        return userDocumentCache.containsKey(userId);
    }

    public static String getCachedContent(String userId) {
        ParsedDocument doc = getValid(userId);
        return doc != null ? doc.content() : null;
    }

    public static String getCachedFileName(String userId) {
        ParsedDocument doc = getValid(userId);
        return doc != null ? doc.fileName() : null;
    }

    public static byte[] getCachedBytes(String userId) {
        ParsedDocument doc = getValid(userId);
        return doc == null || doc.bytes() == null ? null : doc.bytes().clone();
    }

    public static void cacheDocument(String userId, String fileName, String content) {
        cacheDocument(userId, fileName, content, null);
    }

    public static void cacheDocument(String userId, String fileName, String content, byte[] bytes) {
        byte[] safeBytes = bytes == null ? null : bytes.clone();
        ParsedDocument entry = new ParsedDocument(fileName, content, safeBytes, System.currentTimeMillis());
        synchronized (DocumentTools.class) {
            // 替换已有条目时先释放旧条目的字节预算
            ParsedDocument old = userDocumentCache.put(userId, entry);
            if (old != null) {
                totalCachedBytes.addAndGet(-old.memoryEstimate());
            }
            totalCachedBytes.addAndGet(entry.memoryEstimate());
            evictExpiredLocked();
            evictOverBudgetLocked();
        }
        log.info("[DocumentTools] 文档已缓存: userId={}, fileName={}, length={}, originalBytes={}",
                userId, fileName, content.length(), safeBytes == null ? 0 : safeBytes.length);
    }

    public static void clearCachedDocument(String userId) {
        synchronized (DocumentTools.class) {
            ParsedDocument removed = userDocumentCache.remove(userId);
            if (removed != null) {
                totalCachedBytes.addAndGet(-removed.memoryEstimate());
            }
        }
    }

    // ── 下载 + 解析 + 缓存 ─────────────────────────────────────

    /**
     * 下载并解析微信文件消息，将结果缓存到内存。
     *
     * @return 解析成功返回文件名；文件为空/无法提取文字时返回提示文案；下载失败返回 {@code null}
     */
    public static String downloadParseAndCache(WeixinMessage msg, ILinkClient client, String userId,
                                               DocumentParsingService parsingService) {
        Path tempFile = downloadFileFromMessage(msg, client);
        if (tempFile == null) return null;
        try {
            String fileName = extractFileName(msg);
            ParseResult result = parsingService.parse(tempFile, fileName);
            if (result == null || result.text() == null || result.text().isBlank()) {
                return "⚠️ 文件内容为空或无法提取文字（可能是扫描版PDF/图片型文档）";
            }
            byte[] fileBytes = Files.readAllBytes(tempFile);
            cacheDocument(userId, fileName, result.text(), fileBytes);
            log.info("[DocumentTools] 文件解析并缓存成功: name={}, size={}KB", fileName, fileBytes.length / 1024);
            return fileName;
        } catch (Exception e) {
            log.error("[DocumentTools] 文件解析失败: {}", e.getMessage(), e);
            return null;
        } finally {
            deleteQuietly(tempFile);
        }
    }

    @Tool(description = "解析并阅读用户发送的文件内容。支持PDF、Word、Excel、TXT等格式。当用户发送文件并要求总结、分析、翻译、提取信息时调用此工具")
    public String parseDocument(
            @ToolParam(description = "用户对文件的提问或指令，如'帮我总结一下'、'这个表格有什么数据'、'帮我翻译这份文档'") String question) {

        String userId = userContext.getCurrentUserId();
        log.info("[DocumentTools] 被调用: userId={}, question={}", userId, question);

        if (userId == null || !hasCachedDocument(userId)) {
            return "❌ 未检测到文件，请先发送文件";
        }

        String cachedContent = getCachedContent(userId);
        String cachedFileName = getCachedFileName(userId);
        log.info("[DocumentTools] 使用缓存文档: userId={}", userId);

        StringBuilder sb = new StringBuilder();
        sb.append("📄 文件解析结果：\n");
        sb.append("文件名：").append(cachedFileName).append("\n\n");
        sb.append("--- 文档内容 ---\n");
        sb.append(cachedContent).append("\n");
        sb.append("--- 内容结束 ---\n\n");
        if (question != null && !question.isBlank()) {
            sb.append("用户的问题是：").append(question);
        } else {
            sb.append("请根据以上文档内容，给出简要总结。");
        }
        return sb.toString();
    }

    private static Path downloadFileFromMessage(WeixinMessage msg, ILinkClient client) {
        if (msg == null || msg.getItem_list() == null) return null;

        for (MessageItem item : msg.getItem_list()) {
            CDNMedia media = extractFileMedia(item);
            if (media == null) continue;
            try {
                byte[] fileBytes = client.downloadMedia(media);
                if (fileBytes == null || fileBytes.length == 0) {
                    log.warn("[DocumentTools] CDN文件下载返回空");
                    return null;
                }

                String fileName = extractFileName(msg);
                String ext = getExtension(fileName);
                Path tempFile = Files.createTempFile("doc-", "." + ext);
                Files.write(tempFile, fileBytes);
                log.info("[DocumentTools] 文件下载成功: path={}, name={}, size={}KB",
                        tempFile, fileName, fileBytes.length / 1024);
                return tempFile;

            } catch (Exception e) {
                log.error("[DocumentTools] 文件下载/保存失败: {}", e.getMessage(), e);
                return null;
            }
        }
        return null;
    }

    // ── 缓存内部实现 ───────────────────────────────────────────

    private static ParsedDocument getValid(String userId) {
        if (userId == null) return null;
        ParsedDocument doc = userDocumentCache.get(userId);
        if (doc == null) return null;
        if (System.currentTimeMillis() - doc.cachedAt() > cacheTtlMs) {
            clearCachedDocument(userId);
            return null;
        }
        return doc;
    }

    private static void evictExpired() {
        synchronized (DocumentTools.class) {
            evictExpiredLocked();
        }
    }

    private static void evictExpiredLocked() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, ParsedDocument>> it = userDocumentCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ParsedDocument> e = it.next();
            if (now - e.getValue().cachedAt() > cacheTtlMs) {
                totalCachedBytes.addAndGet(-e.getValue().memoryEstimate());
                it.remove();
            }
        }
    }

    private static void evictOverBudgetLocked() {
        // 超条目数或超字节预算时，逐出最旧的条目
        while (userDocumentCache.size() > maxCacheEntries || totalCachedBytes.get() > maxCacheBytes) {
            Map.Entry<String, ParsedDocument> oldest = null;
            for (Map.Entry<String, ParsedDocument> e : userDocumentCache.entrySet()) {
                if (oldest == null || e.getValue().cachedAt() < oldest.getValue().cachedAt()) {
                    oldest = e;
                }
            }
            if (oldest == null) break;
            totalCachedBytes.addAndGet(-oldest.getValue().memoryEstimate());
            userDocumentCache.remove(oldest.getKey());
            log.debug("[DocumentTools] 缓存已满，逐出最旧条目: userId={}", oldest.getKey());
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    private static CDNMedia extractFileMedia(MessageItem item) {
        if (item.getFile_item() != null && item.getFile_item().getMedia() != null) {
            return item.getFile_item().getMedia();
        }
        return null;
    }

    private static String extractFileName(WeixinMessage msg) {
        if (msg.getItem_list() == null) return "unknown.dat";
        for (MessageItem item : msg.getItem_list()) {
            if (item.getFile_item() != null) {
                String name = item.getFile_item().getFile_name();
                if (name != null && !name.isBlank()) return name;
            }
        }
        return "unknown.dat";
    }

    private static String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1).toLowerCase() : "dat";
    }

    private record ParsedDocument(String fileName, String content, byte[] bytes, long cachedAt) {
        /** 粗略内存占用（字节），用于字节预算控制 */
        long memoryEstimate() {
            long size = bytes == null ? 0 : bytes.length;
            if (content != null) size += content.length() * 2L; // char 近似 2 字节
            return size;
        }
    }
}
