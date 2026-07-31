package ykd.ykd.job.model;

import lombok.Data;

/**
 * 简历附件实体，映射表 liepin_resume_asset。
 *
 * <p>存储简历原始文件的元信息（路径、类型、大小、哈希），
 * 与 {@link LiepinResume} 通过 {@code user_id} 一对一关联。
 * 附件文件以 SHA-256 命名存储在 {@code <resume-directory>/<user-hash[:16]>/} 下。</p>
 *
 * <p>唯一约束 {@code (user_id, file_hash)} 保证同一文件不会重复存储。</p>
 */
@Data
public class LiepinResumeAsset {
    private Long id;
    /** 微信用户 ID（唯一） */
    private String userId;
    /** 原始文件名 */
    private String fileName;
    /** 磁盘上的绝对路径 */
    private String filePath;
    /** 文件扩展名（pdf / doc / docx） */
    private String fileType;
    /** 文件大小（字节） */
    private Long fileSize;
    /** SHA-256 哈希，用于去重和完整性校验 */
    private String fileHash;
    /** 创建时间 */
    private String createdAt;
    /** 更新时间 */
    private String updatedAt;
}
