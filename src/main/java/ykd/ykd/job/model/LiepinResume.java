package ykd.ykd.job.model;

import lombok.Data;

/**
 * 简历实体，映射表 liepin_resume。
 *
 * <p>存储简历的纯文本内容（供 AI 匹配打分）。
 * 文件路径、类型、大小、哈希等附件信息来自 {@link LiepinResumeAsset}，
 * 在 {@code LiepinResumeService.find()} 中联表填充。</p>
 */
@Data
public class LiepinResume {
    private Long id;
    /** 微信用户 ID（唯一，每个用户一条记录） */
    private String userId;
    /** 原始文件名 */
    private String fileName;
    /** 解析后的纯文本内容 */
    private String content;
    /** 附件文件磁盘路径（来自 liepin_resume_asset，非本表字段） */
    private String filePath;
    /** 附件文件类型（如 pdf、docx） */
    private String fileType;
    /** 附件文件大小（字节） */
    private Long fileSize;
    /** 附件文件 SHA-256 哈希 */
    private String fileHash;
    /** 最后更新时间 */
    private String updatedAt;
}
