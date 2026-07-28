package ykd.ykd.job.model;

import lombok.Data;

@Data
public class LiepinResumeAsset {
    private Long id;
    private String userId;
    private String fileName;
    private String filePath;
    private String fileType;
    private Long fileSize;
    private String fileHash;
    private String createdAt;
    private String updatedAt;
}