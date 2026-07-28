package ykd.ykd.job.mapper;

import org.apache.ibatis.annotations.*;
import ykd.ykd.job.model.LiepinApplicationRecord;

import java.util.List;

@Mapper
public interface LiepinApplicationRecordMapper {
    String COLUMNS = """
            id, user_id, campaign_id, task_id, posting_id, external_job_key,
            job_name, company_name, resume_id, delivery_mode, status,
            attempt_count, failure_reason, contacted_at, resume_sent_at,
            created_at, updated_at
            """;

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

    @Select("SELECT " + COLUMNS + " FROM liepin_application_record WHERE user_id = #{userId} AND external_job_key = #{jobKey}")
    LiepinApplicationRecord findByUserAndJobKey(@Param("userId") String userId,
                                                 @Param("jobKey") String jobKey);

    @Update("""
            UPDATE liepin_application_record
            SET status = 'CONTACTING', attempt_count = attempt_count + 1,
                contacted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
            """)
    int markContacting(@Param("id") long id);
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

    @Select("""
            SELECT COUNT(*)
            FROM liepin_application_record
            WHERE user_id = #{userId}
              AND status = 'SUCCESS'
              AND date(COALESCE(resume_sent_at, contacted_at, updated_at), 'localtime') = date('now', 'localtime')
            """)
    int countTodaySuccessful(@Param("userId") String userId);

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