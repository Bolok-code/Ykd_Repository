package ykd.ykd.job.model;

public record LiepinSearchRequest(
        String keyword,
        String city,
        Integer minSalaryK,
        Integer maxSalaryK,
        boolean excludeOutsourcing,
        int maxResults) {
}