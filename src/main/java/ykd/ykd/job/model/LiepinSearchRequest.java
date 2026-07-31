package ykd.ykd.job.model;

/**
 * 职位搜索请求参数。
 *
 * @param keyword            搜索关键词
 * @param city               目标城市
 * @param minSalaryK         最低月薪（K），可为 {@code null}
 * @param maxSalaryK         最高月薪（K），可为 {@code null}
 * @param excludeOutsourcing 是否排除外包公司
 * @param maxResults         最大返回结果数
 */
public record LiepinSearchRequest(
        String keyword,
        String city,
        Integer minSalaryK,
        Integer maxSalaryK,
        boolean excludeOutsourcing,
        int maxResults) {
}
