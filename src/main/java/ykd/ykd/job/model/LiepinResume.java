package ykd.ykd.job.model;

import lombok.Data;

@Data
public class LiepinResume {
    private Long id;
    private String userId;
    private String fileName;
    private String content;
    private String filePath;
    private String fileType;
    private Long fileSize;
    private String fileHash;
    private String updatedAt;
}