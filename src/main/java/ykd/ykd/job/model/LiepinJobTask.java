package ykd.ykd.job.model;

import lombok.Data;

@Data
public class LiepinJobTask {
    private Long id;
    private String userId;
    private String keyword;
    private String city;
    private Integer minSalaryK;
    private Integer maxSalaryK;
    private boolean excludeOutsourcing = true;
    private String status;
    private String message;
    private String createdAt;
    private String updatedAt;
}