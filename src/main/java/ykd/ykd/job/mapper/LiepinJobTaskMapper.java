package ykd.ykd.job.mapper;

import org.apache.ibatis.annotations.*;
import ykd.ykd.job.model.LiepinJobTask;

@Mapper
public interface LiepinJobTaskMapper {
    @Insert("""
            INSERT INTO liepin_job_task(
                user_id, keyword, city, min_salary_k, max_salary_k,
                exclude_outsourcing, status, message, created_at, updated_at)
            VALUES(
                #{userId}, #{keyword}, #{city}, #{minSalaryK}, #{maxSalaryK},
                #{excludeOutsourcing}, #{status}, #{message}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """)
    @SelectKey(statement = "SELECT last_insert_rowid()", keyProperty = "id", before = false, resultType = Long.class)
    int insert(LiepinJobTask task);

    @Update("""
            UPDATE liepin_job_task
            SET status = #{status}, message = #{message}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId} AND user_id = #{userId}
            """)
    int updateStatus(@Param("taskId") long taskId,
                     @Param("userId") String userId,
                     @Param("status") String status,
                     @Param("message") String message);

    @Select("""
            SELECT id, user_id, keyword, city, min_salary_k, max_salary_k,
                   exclude_outsourcing, status, message, created_at, updated_at
            FROM liepin_job_task
            WHERE id = #{taskId} AND user_id = #{userId}
            """)
    LiepinJobTask findByIdAndUser(@Param("taskId") long taskId, @Param("userId") String userId);

    @Select("""
            SELECT id, user_id, keyword, city, min_salary_k, max_salary_k,
                   exclude_outsourcing, status, message, created_at, updated_at
            FROM liepin_job_task
            WHERE user_id = #{userId}
            ORDER BY id DESC
            LIMIT 1
            """)
    LiepinJobTask findLatestByUser(@Param("userId") String userId);
}