package ykd.ykd.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import ykd.ykd.document.DocumentParsingService;
import ykd.ykd.document.ParseResult;
import ykd.ykd.rag.config.RagProperties;
import ykd.ykd.rag.mapper.KnowledgeDocumentMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.List;

/**
 * 知识库自动入库服务。
 * 应用启动时扫描配置目录中的文档，解析后自动写入 RAG 知识库，
 * 解决知识库 0 数据的问题。
 */
@Slf4j
@Component
public class KnowledgeBaseAutoIngestionService implements ApplicationRunner {

    private static final List<String> SUPPORTED_EXTENSIONS = List.of(
            ".pdf", ".docx", ".doc", ".xlsx", ".xls", ".txt", ".csv", ".md"
    );

    private static final String SYSTEM_USER_ID = "system";

    private final RagProperties ragProperties;
    private final DocumentParsingService parsingService;
    private final DocumentIngestionService ingestionService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeDocumentMapper documentMapper;

    public KnowledgeBaseAutoIngestionService(
            RagProperties ragProperties,
            DocumentParsingService parsingService,
            DocumentIngestionService ingestionService,
            KnowledgeBaseService knowledgeBaseService,
            KnowledgeDocumentMapper documentMapper) {
        this.ragProperties = ragProperties;
        this.parsingService = parsingService;
        this.ingestionService = ingestionService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.documentMapper = documentMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!ragProperties.isAutoIngestEnabled()) {
            log.info("[AutoIngest] 自动入库未启用，跳过");
            return;
        }

        if (!ragProperties.isEnabled()) {
            log.warn("[AutoIngest] RAG 功能未启用，跳过自动入库");
            return;
        }

        String ingestDirPath = ragProperties.getAutoIngestDir();
        Path ingestDir = Path.of(ingestDirPath);

        if (!Files.isDirectory(ingestDir)) {
            log.info("[AutoIngest] 自动入库目录不存在，跳过: {}", ingestDir.toAbsolutePath());
            return;
        }

        log.info("[AutoIngest] 开始扫描自动入库目录: {}", ingestDir.toAbsolutePath());

        Path doneDir = ingestDir.resolve(".ingested");
        try {
            Files.createDirectories(doneDir);
        } catch (IOException e) {
            log.error("[AutoIngest] 无法创建 .ingested 目录: {}", e.getMessage());
            return;
        }

        int ingestedCount = 0;
        int failedCount = 0;

        try (var files = Files.list(ingestDir)) {
            List<Path> candidateFiles = files
                    .filter(Files::isRegularFile)
                    .filter(f -> {
                        String name = f.getFileName().toString().toLowerCase();
                        return SUPPORTED_EXTENSIONS.stream().anyMatch(name::endsWith);
                    })
                    .toList();

            if (candidateFiles.isEmpty()) {
                log.info("[AutoIngest] 未发现可入库的文件");
                return;
            }

            log.info("[AutoIngest] 发现 {} 个候选文件", candidateFiles.size());

            for (Path file : candidateFiles) {
                String fileName = file.getFileName().toString();
                try {
                    log.info("[AutoIngest] 正在处理: {}", fileName);

                    // 1. 解析文档
                    ParseResult parseResult = parsingService.parse(file, fileName);
                    if (parseResult == null || parseResult.text() == null || parseResult.text().isBlank()) {
                        log.warn("[AutoIngest] 文档解析结果为空: {}", fileName);
                        failedCount++;
                        moveToDone(file, doneDir);
                        continue;
                    }

                    // 2. 通过内容哈希去重（与 ingestDocument 内置的哈希校验一致）
                    String contentHash = calculateHash(parseResult.text());
                    if (documentMapper.findByUserIdAndFileHash(SYSTEM_USER_ID, contentHash) != null) {
                        log.info("[AutoIngest] 文档已存在于知识库（哈希匹配），跳过: {}", fileName);
                        moveToDone(file, doneDir);
                        continue;
                    }

                    // 3. 入库
                    String fileType = parseResult.fileType() != null ? parseResult.fileType() : detectFileType(fileName);
                    Long documentId = ingestionService.ingestDocument(
                            SYSTEM_USER_ID,
                            fileName,
                            fileType,
                            parseResult.text()
                    );

                    log.info("[AutoIngest] 入库成功: fileName={}, documentId={}, textLength={}",
                            fileName, documentId, parseResult.text().length());
                    ingestedCount++;

                    // 4. 成功后移动到 .ingested 目录
                    moveToDone(file, doneDir);

                } catch (Exception e) {
                    log.error("[AutoIngest] 入库失败: fileName={}, error={}", fileName, e.getMessage(), e);
                    failedCount++;
                    // 失败的文件也移走，避免每次都重试失败
                    moveToDone(file, doneDir);
                }
            }

        } catch (IOException e) {
            log.error("[AutoIngest] 扫描目录失败: {}", e.getMessage(), e);
        }

        log.info("[AutoIngest] 自动入库完成: 成功={}, 失败={}", ingestedCount, failedCount);
    }

    private void moveToDone(Path file, Path doneDir) {
        try {
            Path target = doneDir.resolve(file.getFileName().toString());
            // 如果目标已存在，加时间戳避免冲突
            if (Files.exists(target)) {
                String baseName = file.getFileName().toString();
                int dot = baseName.lastIndexOf('.');
                String nameWithoutExt = dot > 0 ? baseName.substring(0, dot) : baseName;
                String ext = dot > 0 ? baseName.substring(dot) : "";
                target = doneDir.resolve(nameWithoutExt + "_" + System.currentTimeMillis() + ext);
            }
            moveFileSafely(file, target);
            log.debug("[AutoIngest] 文件已移动到: {}", target);
        } catch (IOException e) {
            log.warn("[AutoIngest] 移动文件失败: {} -> {}, 将隔离失败文件: {}",
                    file, doneDir, e.getMessage());
            quarantineFailed(file);
        }
    }

    /**
     * 尽力移动文件：优先原子移动，失败退化为普通移动（跨文件系统时自动 copy+delete）。
     */
    private void moveFileSafely(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(source, target);
        }
    }

    /**
     * 移动失败时把文件重命名为 {@code <name>.failed.<ts>}，
     * 使其不再匹配支持的扩展名，避免下次启动对同一损坏文件无限重试。
     */
    private void quarantineFailed(Path file) {
        try {
            Path quarantined = file.resolveSibling(
                    file.getFileName() + ".failed." + System.currentTimeMillis());
            Files.move(file, quarantined);
            log.warn("[AutoIngest] 失败文件已隔离，不再重试: {}", quarantined);
        } catch (IOException e) {
            log.error("[AutoIngest] 隔离失败文件失败: {} -> error={}", file, e.getMessage());
        }
    }

    private String detectFileType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".docx") || lower.endsWith(".doc")) return "Word";
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) return "Excel";
        if (lower.endsWith(".md")) return "Markdown";
        return "文本";
    }

    private String calculateHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();
        } catch (Exception e) {
            // 确定性兜底：绝不能带时间戳，否则每次哈希都不同、去重完全失效
            String text = content == null ? "" : content;
            return "fallback_" + text.length() + "_" + Integer.toHexString(text.hashCode());
        }
    }
}
