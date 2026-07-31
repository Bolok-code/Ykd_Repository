package ykd.ykd.job.mapper;

import org.apache.ibatis.annotations.*;
import ykd.ykd.job.model.LiepinApplicationRecord;

import java.util.List;

/**
 * 投递记录 Mapper，操作表 liepin_application_record。
 *
 * <p>提供投递记录的去重插入、状态流转、今日统计和历史查询。
 * 核心去重机制：通过 {@code INSERT OR IGNORE} + {@code (user_id, external_job_key)} 唯一约束。</p>
 */
@Mapper
public interface LiepinApplicationRecordMapper {
    String COLUMNS = """
            id, user_id, campaign_id, task_id, posting_id, external_job_key,
            job_name, company_name, resume_id, delivery_mode, status,
            attempt_count, failure_reason, contacted_at, resume_sent_at,
            created_at, updated_at
            """;

    /**
     * 插入投递记录（去重）。如果 {@code (user_id, external_job_key)} 已存在则忽略。
     *
     * @param record 投递记录
     * @return 影响行数（INSERT OR IGNORE 时重复行为 0）
     */
    @Insert("""
            INSERT OR IGNORE INTO liepin_application_record(
                user_id, campaign_id, task_id, posting_id, external_job_key,
                job_name, company_name, resume_id, delivery_mode, status,
                attempt_count, created_at, updated_at)
            VALUES(
                #{userId}, #{campaignId}, #{taskId}, #{postingId}, #{externalJobKey},
                #{jobName}, #{companyName}, #{resumeId}, #{deliveryMode}, #{status},
                0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """)
    int insertIfAbsent(LiepinApplicationRecord record);

    /**
     * 按用户和职位唯一键查询投递记录，用于去重判断。
     */
    @Select("SELECT " + COLUMNS + " FROM liepin_application_record WHERE user_id = #{userId} AND external_job_key = #{jobKey}")
    LiepinApplicationRecord findByUserAndJobKey(@Param("userId") String userId,
                                                 @Param("jobKey") String jobKey);

    /**
     * 标记记录为"联系中"，递增尝试次数，记录联系时间。
     */
    @Update("""
            UPDATE liepin_application_record
            SET status = 'CONTACTING', attempt_count = attempt_count + 1,
                contacted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
            """)
    int markContacting(@Param("id") long id);

    /**
     * 更新投递结果状态。
     *
     * @param contacted 是否更新联系时间（投递成功/联系时设为 true）
     * @param sent      是否更新简历发送时间（简历投递成功时设为 true）
     */
    @Update("""
            UPDATE liepin_application_record
            SET status = #{status}, failure_reason = #{failureReason},
                contacted_at = CASE WHEN #{contacted} = 1 THEN CURRENT_TIMESTAMP ELSE contacted_at END,
                resume_sent_at = CASE WHEN #{sent} = 1 THEN CURRENT_TIMESTAMP ELSE resume_sent_at END,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
            """)
    int updateResult(@Param("id") long id,
                     @Param("status") String status,
                     @Param("failureReason") String failureReason,
                     @Param("contacted") boolean contacted,
                     @Param("sent") boolean sent);

    /**
     * 统计当前用户在本地今天成功的投递次数。
     * 用于每日限额判断。
     */
    @Select("""
            SELECT COUNT(*)
            FROM liepin_application_record
            WHERE user_id = #{userId}
              AND status = 'SUCCESS'
              AND date(COALESCE(resume_sent_at, contacted_at, updated_at), 'localtime') = date('now', 'localtime')
            """)
    int countTodaySuccessful(@Param("userId") String userId);

    /**
     * 查询用户最近 N 条投递记录（按 id 倒序）。
     */
    @Select("""
            SELECT """ + COLUMNS + """
            FROM liepin_application_record
            WHERE user_id = #{userId}
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<LiepinApplicationRecord> findLatestByUser(@Param("userId") String userId,
                                                    @Param("limit") int limit);
}
