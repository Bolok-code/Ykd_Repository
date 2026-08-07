package ykd.ykd.job.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import ykd.ykd.job.config.LiepinProperties;
import ykd.ykd.job.mapper.LiepinResumeAssetMapper;
import ykd.ykd.job.mapper.LiepinResumeMapper;
import ykd.ykd.job.model.LiepinResume;
import ykd.ykd.job.model.LiepinResumeAsset;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class LiepinResumeService {
    private static final Set<String> ATTACHMENT_TYPES = Set.of("pdf", "doc", "docx");

    private final LiepinResumeMapper resumeMapper;
    private final LiepinResumeAssetMapper assetMapper;
    private final LiepinProperties properties;

    public LiepinResumeService(LiepinResumeMapper resumeMapper,
                               LiepinResumeAssetMapper assetMapper,
                               LiepinProperties properties) {
        this.resumeMapper = resumeMapper;
        this.assetMapper = assetMapper;
        this.properties = properties;
    }

    public void save(String userId, String fileName, String content) {
        save(userId, fileName, content, null);
    }

    public void save(String userId, String fileName, String content, byte[] originalBytes) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(content)) {
            throw new IllegalArgumentException("用户标识和简历内容不能为空");
        }
        LiepinResume resume = new LiepinResume();
        resume.setUserId(userId);
        resume.setFileName(fileName);
        resume.setContent(content);
        resumeMapper.upsert(resume);

        if (originalBytes != null && originalBytes.length > 0) {
            LiepinResumeAsset oldAsset = assetMapper.findByUserId(userId);
            // 只有确实写入了新的附件文件才清理旧附件；
            // 非附件格式（如 md/txt）不写入新附件，此时必须保留旧附件，
            // 避免把技术文档误存为简历时删掉用户真实的简历文件。
            String newFilePath = persistAsset(userId, fileName, originalBytes);
            if (newFilePath != null && oldAsset != null) {
                String oldPath = oldAsset.getFilePath();
                if (oldPath != null && !oldPath.equals(newFilePath)) {
                    deleteFileQuietly(oldPath);
                }
            }
        }
    }

    /** 删除磁盘上的文件（静默失败）。 */
    private void deleteFileQuietly(String filePath) {
        try {
            Path file = Path.of(filePath);
            if (Files.isRegularFile(file)) {
                Files.deleteIfExists(file);
                log.info("[ResumeService] 已删除旧附件文件: {}", file);
            }
        } catch (IOException e) {
            log.warn("[ResumeService] 删除旧附件文件失败: path={}, error={}",
                    filePath, e.getMessage());
        }
    }

    public LiepinResume find(String userId) {
        LiepinResume resume = resumeMapper.findByUserId(userId);
        if (resume == null) return null;
        LiepinResumeAsset asset = assetMapper.findByUserId(userId);
        if (asset != null) {
            resume.setFilePath(asset.getFilePath());
            resume.setFileType(asset.getFileType());
            resume.setFileSize(asset.getFileSize());
            resume.setFileHash(asset.getFileHash());
        }
        return resume;
    }

    public boolean hasUsableAttachment(LiepinResume resume) {
        if (resume == null || !StringUtils.hasText(resume.getFilePath())) return false;
        try {
            Path path = Path.of(resume.getFilePath()).toAbsolutePath().normalize();
            Path root = Path.of(properties.getResumeDirectory()).toAbsolutePath().normalize();
            return path.startsWith(root) && Files.isRegularFile(path) && Files.size(path) > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 简历章节关键词：命中 >=2 且含身份特征才判定为疑似简历 */
    private static final List<String> RESUME_SECTION_KEYWORDS =
            List.of("教育经历", "工作经历", "项目经历", "专业技能", "求职意向", "个人信息");
    /** 简历身份特征：真实简历通常包含姓名或联系方式 */
    private static final List<String> RESUME_IDENTITY_KEYWORDS =
            List.of("姓名", "电话", "手机", "邮箱", "性别", "年龄", "出生日期", "求职意向", "期望薪资", "工作年限");
    /** 文件名中的技术文档标记：命中且文件名不含"简历"时直接排除 */
    private static final List<String> TECH_DOC_NAME_MARKERS =
            List.of("详解", "说明", "技术", "模块", "文档", "手册", "教程", "设计", "架构", "分析", "指南", "readme");
    /** 内容中的技术排版特征：真实简历文本极少出现 */
    private static final List<String> TECH_DOC_CONTENT_MARKERS =
            List.of("```", "## ", "### ", "调用链路", "数据库表", "配置文件", "架构总览", "目录", "源码");

    public boolean looksLikeResume(String fileName, String content) {
        String name = fileName == null ? "" : fileName;
        String nameLower = name.toLowerCase(Locale.ROOT);
        String body = content == null ? "" : content;

        // 1. 文件名明确包含 resume
        if (nameLower.contains("resume")) return true;

        // 2. 文件名带明显技术文档标记时排除（除非文件名本身含"简历"）
        boolean techDocName = TECH_DOC_NAME_MARKERS.stream().anyMatch(nameLower::contains);
        if (techDocName && !nameLower.contains("简历")) return false;

        // 3. Markdown/技术排版特征：真实简历文本极少出现
        if (TECH_DOC_CONTENT_MARKERS.stream().anyMatch(body::contains)) return false;

        // 4. 文件名含"简历"且有简历章节关键词 → 高置信
        if (nameLower.contains("简历")
                && RESUME_SECTION_KEYWORDS.stream().anyMatch(body::contains)) {
            return true;
        }

        // 5. 内容判定：至少 2 个简历章节关键词 + 至少 1 个身份特征
        int sectionHits = countHits(RESUME_SECTION_KEYWORDS, body);
        int identityHits = countHits(RESUME_IDENTITY_KEYWORDS, body);
        return sectionHits >= 2 && identityHits >= 1;
    }

    private static int countHits(List<String> keywords, String source) {
        int hits = 0;
        for (String keyword : keywords) {
            if (source.contains(keyword)) hits++;
        }
        return hits;
    }

    /**
     * 将附件写入磁盘并更新附件记录。
     *
     * @return 新附件文件的绝对路径；非附件格式（未写入）时返回 {@code null}
     */
    private String persistAsset(String userId, String fileName, byte[] bytes) {
        String extension = extension(fileName);
        if (!ATTACHMENT_TYPES.contains(extension)) return null;
        String fileHash = sha256(bytes);
        String userFolder = sha256(userId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 16);
        Path root = Path.of(properties.getResumeDirectory()).toAbsolutePath().normalize();
        Path directory = root.resolve(userFolder).normalize();
        Path target = directory.resolve(fileHash + "." + extension).normalize();
        if (!target.startsWith(root)) throw new IllegalStateException("简历保存路径越界");
        try {
            Files.createDirectories(directory);
            if (!Files.exists(target)) {
                Path temporary = Files.createTempFile(directory, "resume-", ".tmp");
                Files.write(temporary, bytes);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("保存简历原文件失败：" + e.getMessage(), e);
        }

        LiepinResumeAsset asset = new LiepinResumeAsset();
        asset.setUserId(userId);
        asset.setFileName(StringUtils.hasText(fileName) ? fileName : "resume." + extension);
        asset.setFilePath(target.toString());
        asset.setFileType(extension);
        asset.setFileSize((long) bytes.length);
        asset.setFileHash(fileHash);
        assetMapper.upsert(asset);
        return target.toString();
    }

    private String extension(String fileName) {
        if (!StringUtils.hasText(fileName)) return "dat";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "dat" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前JDK不支持SHA-256", e);
        }
    }
}
