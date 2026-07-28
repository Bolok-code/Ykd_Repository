package ykd.ykd.job.mapper;

import org.apache.ibatis.annotations.*;
import ykd.ykd.job.model.LiepinJobCampaign;

import java.util.List;

@Mapper
public interface LiepinJobCampaignMapper {
    String COLUMNS = """
            id, user_id, name, resume_id, delivery_mode, keyword, city,
            min_salary_k, max_salary_k, min_match_score, exclude_outsourcing,
            excluded_keywords, daily_limit, interval_minutes, status,
            consecutive_failures, message, last_run_at, next_run_at,
            created_at, updated_at
            """;

    @Insert("""
            INSERT INTO liepin_job_campaign(
                user_id, name, resume_id, delivery_mode, keyword, city,
                min_salary_k, max_salary_k, min_match_score, exclude_outsourcing,
                excluded_keywords, daily_limit, interval_minutes, status,
                consecutive_failures, message, next_run_at, created_at, updated_at)
            VALUES(
                #{userId}, #{name}, #{resumeId}, #{deliveryMode}, #{keyword}, #{city},
                #{minSalaryK}, #{maxSalaryK}, #{minMatchScore}, #{excludeOutsourcing},
                #{excludedKeywords}, #{dailyLimit}, #{intervalMinutes}, #{status},
                0, #{message}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """)
    @SelectKey(statement = "SELECT last_insert_rowid()", keyProperty = "id", before = false, resultType = Long.class)
    int insert(LiepinJobCampaign campaign);

    @Select("SELECT " + COLUMNS + " FROM liepin_job_campaign WHERE id = #{id} AND user_id = #{userId}")
    LiepinJobCampaign findByIdAndUser(@Param("id") long id, @Param("userId") String userId);

    @Select("SELECT " + COLUMNS + " FROM liepin_job_campaign WHERE id = #{id}")
    LiepinJobCampaign findById(@Param("id") long id);

    @Select("""
        SELECT
        """ + COLUMNS + """
        FROM liepin_job_campaign
        WHERE user_id = #{userId}
        ORDER BY id DESC
        LIMIT 1
        """)
    LiepinJobCampaign findLatestByUser(@Param("userId") String userId);

    @Select("""
        SELECT
        """ + COLUMNS + """
        FROM liepin_job_campaign
        WHERE status = 'RUNNING'
          AND (next_run_at IS NULL OR next_run_at <= CURRENT_TIMESTAMP)
        ORDER BY id ASC
        """)
    List<LiepinJobCampaign> findDueRunning();

    @Select("""
        SELECT
        """ + COLUMNS + """
        FROM liepin_job_campaign
        WHERE status = 'LOGIN_REQUIRED'
        ORDER BY id ASC
        """)
    List<LiepinJobCampaign> findLoginRequired();

    @Update("""
            UPDATE liepin_job_campaign
            SET status = #{status}, message = #{message}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int updateStatus(@Param("id") long id,
                     @Param("userId") String userId,
                     @Param("status") String status,
                     @Param("message") String message);

    @Update("""
            UPDATE liepin_job_campaign
            SET status = 'RUNNING', consecutive_failures = 0,
                message = #{message}, next_run_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int start(@Param("id") long id,
              @Param("userId") String userId,
              @Param("message") String message);

    @Update("""
            UPDATE liepin_job_campaign
            SET status = #{status}, message = #{message},
                consecutive_failures = #{consecutiveFailures},
                last_run_at = CURRENT_TIMESTAMP,
                next_run_at = datetime(CURRENT_TIMESTAMP, '+' || interval_minutes || ' minutes'),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
            """)
    int finishRun(@Param("id") long id,
                  @Param("status") String status,
                  @Param("message") String message,
                  @Param("consecutiveFailures") int consecutiveFailures);
}