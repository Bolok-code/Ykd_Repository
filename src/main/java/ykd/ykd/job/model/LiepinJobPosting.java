package ykd.ykd.job.model;

import lombok.Data;

@Data
public class LiepinJobPosting {
    private Long id;
    private Long taskId;
    private String externalJobId;
    private String jobName;
    private String companyName;
    private String companyIndustry;
    private String companyScale;
    private String city;
    private String salary;
    private String education;
    private String experience;
    private String recruiterName;
    private String recruiterTitle;
    private String recruiterImId;
    private String publishedAt;
    private String description;
    private String jobUrl;
    private Integer matchScore;
    private String matchReason;
    private String greeting;
    private String status = "CANDIDATE";
    private String createdAt;
}