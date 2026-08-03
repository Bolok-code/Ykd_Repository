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
            // 先删旧附件文件，再保存新的
            deleteOldAsset(userId);
            persistAsset(userId, fileName, originalBytes);
        }
    }

    /**
     * 删除用户旧的简历附件（磁盘文件 + DB 记录）。
     * 更换简历时调用，避免旧文件堆积。
     */
    private void deleteOldAsset(String userId) {
        LiepinResumeAsset oldAsset = assetMapper.findByUserId(userId);
        if (oldAsset != null && oldAsset.getFilePath() != null) {
            try {
                Path oldFile = Path.of(oldAsset.getFilePath());
                if (Files.isRegularFile(oldFile)) {
                    Files.deleteIfExists(oldFile);
                    log.info("[ResumeService] 已删除旧附件文件: {}", oldFile);
                }
            } catch (IOException e) {
                log.warn("[ResumeService] 删除旧附件文件失败: path={}, error={}",
                        oldAsset.getFilePath(), e.getMessage());
            }
            assetMapper.deleteByUserId(userId);
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

    public boolean looksLikeResume(String fileName, String content) {
        String source = (fileName == null ? "" : fileName) + "\n" + (content == null ? "" : content);
        int hits = 0;
        for (String keyword : new String[]{"简历", "教育经历", "工作经历", "项目经历", "专业技能", "求职意向", "个人信息"}) {
            if (source.contains(keyword)) hits++;
        }
        return hits >= 2 || (fileName != null && fileName.toLowerCase(Locale.ROOT).contains("resume"));
    }

    private void persistAsset(String userId, String fileName, byte[] bytes) {
        String extension = extension(fileName);
        if (!ATTACHMENT_TYPES.contains(extension)) return;
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