package ykd.ykd.job.mapper;

import org.apache.ibatis.annotations.*;
import ykd.ykd.job.model.LiepinJobPosting;

import java.util.List;

/**
 * 职位信息 Mapper，操作表 liepin_job_posting。
 *
 * <p>存储从猎聘搜索到的职位，按任务 ID 分组，按匹配分数降序排列。</p>
 */
@Mapper
public interface LiepinJobPostingMapper {
    String COLUMNS = """
            id, task_id, external_job_id, job_name, company_name, company_industry,
            company_scale, city, salary, education, experience, recruiter_name,
            recruiter_title, recruiter_im_id, published_at, description, job_url,
            match_score, match_reason, greeting, status, created_at
            """;

    /** 插入一条职位记录，回填自增 ID。 */
    @Insert("""
            INSERT INTO liepin_job_posting(
                task_id, external_job_id, job_name, company_name, company_industry,
                company_scale, city, salary, education, experience, recruiter_name,
                recruiter_title, recruiter_im_id, published_at, description, job_url,
                match_score, match_reason, greeting, status, created_at)
            VALUES(
                #{taskId}, #{externalJobId}, #{jobName}, #{companyName}, #{companyIndustry},
                #{companyScale}, #{city}, #{salary}, #{education}, #{experience}, #{recruiterName},
                #{recruiterTitle}, #{recruiterImId}, #{publishedAt}, #{description}, #{jobUrl},
                #{matchScore}, #{matchReason}, #{greeting}, #{status}, CURRENT_TIMESTAMP)
            """)
    @SelectKey(statement = "SELECT last_insert_rowid()", keyProperty = "id", before = false, resultType = Long.class)
    int insert(LiepinJobPosting posting);

    /** 查询某个搜索任务下的所有职位，按匹配分数降序。 */
    @Select("SELECT " + COLUMNS + " FROM liepin_job_posting WHERE task_id = #{taskId} ORDER BY match_score DESC, id ASC")
    List<LiepinJobPosting> findByTaskId(@Param("taskId") long taskId);

    /** 按 ID 和任务 ID 查询单条职位（防止跨任务访问）。 */
    @Select("SELECT " + COLUMNS + " FROM liepin_job_posting WHERE id = #{postingId} AND task_id = #{taskId}")
    LiepinJobPosting findByIdAndTask(@Param("postingId") long postingId, @Param("taskId") long taskId);

    /** 更新职位状态（如标记为已投递）。 */
    @Update("UPDATE liepin_job_posting SET status = #{status} WHERE id = #{postingId}")
    int updateStatus(@Param("postingId") long postingId, @Param("status") String status);
}
