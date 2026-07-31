package ykd.ykd.job.mapper;

import org.apache.ibatis.annotations.*;
import ykd.ykd.job.model.LiepinJobCampaign;

import java.util.List;

/**
 * 投递计划 Mapper，操作表 liepin_job_campaign。
 *
 * <p>管理自动投递计划的完整生命周期：创建、查询、状态更新、执行完成回写。
 * 调度器通过 {@link #findDueRunning()} 拉取到期计划，执行后通过 {@link #finishRun(long, String, String, int)} 回写.</p>
 */
@Mapper
public interface LiepinJobCampaignMapper {
    String COLUMNS = """
            id, user_id, name, resume_id, delivery_mode, keyword, city,
            min_salary_k, max_salary_k, min_match_score, exclude_outsourcing,
            excluded_keywords, daily_limit, interval_minutes, status,
            consecutive_failures, message, last_run_at, next_run_at,
            created_at, updated_at
            """;

    /**
     * 插入新计划。通过 {@code @SelectKey} 回填自增 ID。
     */
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

    /** 按 ID + 用户 ID 查询（安全校验，防止跨用户操作）。 */
    @Select("SELECT " + COLUMNS + " FROM liepin_job_campaign WHERE id = #{id} AND user_id = #{userId}")
    LiepinJobCampaign findByIdAndUser(@Param("id") long id, @Param("userId") String userId);

    /** 仅按 ID 查询。 */
    @Select("SELECT " + COLUMNS + " FROM liepin_job_campaign WHERE id = #{id}")
    LiepinJobCampaign findById(@Param("id") long id);

    /** 查询用户最新的计划（按 id 倒序取第一条）。 */
    @Select("""
        SELECT
        """ + COLUMNS + """
        FROM liepin_job_campaign
        WHERE user_id = #{userId}
        ORDER BY id DESC
        LIMIT 1
        """)
    LiepinJobCampaign findLatestByUser(@Param("userId") String userId);

    /**
     * 查询所有到期且状态为 RUNNING 的计划。
     * 到期条件：{@code next_run_at IS NULL 或 <= 当前时间}。
     */
    @Select("""
        SELECT
        """ + COLUMNS + """
        FROM liepin_job_campaign
        WHERE status = 'RUNNING'
          AND (next_run_at IS NULL OR next_run_at <= CURRENT_TIMESTAMP)
        ORDER BY id ASC
        """)
    List<LiepinJobCampaign> findDueRunning();

    /** 查询所有登录过期的计划，用于扫描时自动恢复。 */
    @Select("""
        SELECT
        """ + COLUMNS + """
        FROM liepin_job_campaign
        WHERE status = 'LOGIN_REQUIRED'
        ORDER BY id ASC
        """)
    List<LiepinJobCampaign> findLoginRequired();

    /**
     * 更新计划状态（通用）。
     */
    @Update("""
            UPDATE liepin_job_campaign
            SET status = #{status}, message = #{message}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int updateStatus(@Param("id") long id,
                     @Param("userId") String userId,
                     @Param("status") String status,
                     @Param("message") String message);

    /**
     * 启动计划：状态设为 RUNNING，重置连续失败计数，设置下次执行时间为现在。
     */
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

    /**
     * 完成本次执行，回写状态和调度信息。
     * 下次执行时间 = 当前时间 + {@code interval_minutes}。
     */
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
