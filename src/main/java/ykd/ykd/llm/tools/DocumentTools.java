package ykd.ykd.llm.tools;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.CDNMedia;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import ykd.ykd.document.ParseResult;
import ykd.ykd.exception.ErrorCode;
import ykd.ykd.document.DocumentParsingService;
import ykd.ykd.processor.UserContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class DocumentTools {

    private final DocumentParsingService documentParsingService;
    private final UserContext userContext;

    private static final ThreadLocal<FileContext> currentFile = new ThreadLocal<>();
    private static final Map<String, ParsedDocument> userDocumentCache = new ConcurrentHashMap<>();

    public DocumentTools(DocumentParsingService documentParsingService,
                         UserContext userContext) {
        this.documentParsingService = documentParsingService;
        this.userContext = userContext;
    }

    public static void setCurrentFile(WeixinMessage msg, ILinkClient client) {
        currentFile.set(new FileContext(msg, client));
    }

    public static void clearCurrentFile() {
        currentFile.remove();
    }

    public static boolean hasCachedDocument(String userId) {
        return userDocumentCache.containsKey(userId);
    }

    public static String getCachedContent(String userId) {
        ParsedDocument doc = userDocumentCache.get(userId);
        return doc != null ? doc.content() : null;
    }

    public static String getCachedFileName(String userId) {
        ParsedDocument doc = userDocumentCache.get(userId);
        return doc != null ? doc.fileName() : null;
    }

    public static void cacheDocument(String userId, String fileName, String content) {
        userDocumentCache.put(userId, new ParsedDocument(fileName, content));
        log.info("[DocumentTools] 文档已缓存: userId={}, fileName={}, length={}", userId, fileName, content.length());
    }

    public static void clearCachedDocument(String userId) {
        userDocumentCache.remove(userId);
    }

    public static String downloadParseAndCache(WeixinMessage msg, ILinkClient client, String userId,
                                               DocumentParsingService parsingService) {
        if (msg.getItem_list() == null) return null;

        for (MessageItem item : msg.getItem_list()) {
            CDNMedia media = extractFileMedia(item);
            if (media != null) {
                Path tempFile = null;
                try {
                    byte[] fileBytes = client.downloadMedia(media);
                    if (fileBytes == null || fileBytes.length == 0) {
                        log.warn("[DocumentTools] CDN文件下载返回空");
                        return null;
                    }

                    String fileName = extractFileName(msg);
                    String ext = getExtension(fileName);
                    tempFile = Files.createTempFile("doc-", "." + ext);
                    Files.write(tempFile, fileBytes);
                    log.info("[DocumentTools] 文件下载成功: name={}, size={}KB", fileName, fileBytes.length / 1024);

                    ParseResult result = parsingService.parse(tempFile, fileName);

                    if (result.text().isBlank()) {
                        return "⚠️ 文件内容为空或无法提取文字（可能是扫描版PDF/图片型文档）";
                    }

                    cacheDocument(userId, fileName, result.text());
                    return fileName;

                } catch (Exception e) {
                    log.error("[DocumentTools] 文件解析失败: {}", e.getMessage(), e);
                    return null;
                } finally {
                    if (tempFile != null) {
                        try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
                    }
                }
            }
        }
        return null;
    }


    @Tool(description = "解析并阅读用户发送的文件内容。支持PDF、Word、Excel、TXT等格式。当用户发送文件并要求总结、分析、翻译、提取信息时调用此工具")
    public String parseDocument(
            @ToolParam(description = "用户对文件的提问或指令，如'帮我总结一下'、'这个表格有什么数据'、'帮我翻译这份文档'") String question) {

        String userId = userContext.getCurrentUserId();
        log.info("[DocumentTools] 被调用: userId={}, question={}", userId, question);

        FileContext ctx = currentFile.get();
        if (ctx == null) {
            return "❌ 未检测到文件，请先发送文件";
        }

        Path tempFile = null;
        try {
            tempFile = downloadFileFromMessage(ctx.msg, ctx.client);
            if (tempFile == null) {
                return "❌ 文件下载失败";
            }

            String fileName = extractFileName(ctx.msg);
            ParseResult result = documentParsingService.parse(tempFile, fileName);

            if (result.text().isBlank()) {
                return "⚠️ 文件内容为空或无法提取文字（可能是扫描版PDF/图片型文档）";
            }

            cacheDocument(userId, fileName, result.text());

            return buildResponse(result, question, fileName);

        } catch (UnsupportedOperationException e) {
            return "⚠️ " + e.getMessage() + "\n目前支持：PDF、Word(.docx)、Excel(.xlsx)、TXT、CSV、Markdown";
        } catch (Exception e) {
            log.error("[DocumentTools] 解析失败: {}", e.getMessage(), e);
            return "❌ " + ErrorCode.DOCUMENT_PARSE_FAILED.getDefaultMessage() + "：" + e.getMessage();
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private String buildResponse(ParseResult result, String question, String fileName) {
        StringBuilder sb = new StringBuilder();
        sb.append("📄 文件解析结果：\n");
        sb.append("文件名：").append(fileName).append("\n");
        sb.append("类型：").append(result.fileType()).append("\n");
        sb.append("元素数：").append(result.elementCount()).append("\n\n");
        sb.append("--- 文档内容 ---\n");
        sb.append(result.text()).append("\n");
        sb.append("--- 内容结束 ---\n\n");

        if (question != null && !question.isBlank()) {
            sb.append("用户的问题是：").append(question);
        } else {
            sb.append("请根据以上文档内容，给出简要总结。");
        }
        return sb.toString();
    }

    private Path downloadFileFromMessage(WeixinMessage msg, ILinkClient client) {
        if (msg.getItem_list() == null) return null;

        for (MessageItem item : msg.getItem_list()) {
            CDNMedia media = extractFileMedia(item);
            if (media != null) {
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
                    log.info("[DocumentTools] 文件保存成功: path={}, name={}, size={}KB",
                            tempFile, fileName, fileBytes.length / 1024);
                    return tempFile;

                } catch (Exception e) {
                    log.error("[DocumentTools] 文件下载/保存失败: {}", e.getMessage(), e);
                    return null;
                }
            }
        }
        return null;
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

    private record FileContext(WeixinMessage msg, ILinkClient client) {
    }

    private record ParsedDocument(String fileName, String content) {
    }
}