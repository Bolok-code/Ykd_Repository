package ykd.ykd.job.mapper;

import org.apache.ibatis.annotations.*;
import ykd.ykd.job.model.LiepinJobTask;

/**
 * 搜索任务 Mapper，操作表 liepin_job_task。
 *
 * <p>每次搜索（手动或自动）创建一条任务记录，通过状态字段追踪任务进度。</p>
 */
@Mapper
public interface LiepinJobTaskMapper {

    /** 插入新任务，回填自增 ID。 */
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

    /**
     * 更新任务状态和消息。
     */
    @Update("""
            UPDATE liepin_job_task
            SET status = #{status}, message = #{message}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId} AND user_id = #{userId}
            """)
    int updateStatus(@Param("taskId") long taskId,
                     @Param("userId") String userId,
                     @Param("status") String status,
                     @Param("message") String message);

    /** 按 ID + 用户 ID 查询任务（安全校验）。 */
    @Select("""
            SELECT id, user_id, keyword, city, min_salary_k, max_salary_k,
                   exclude_outsourcing, status, message, created_at, updated_at
            FROM liepin_job_task
            WHERE id = #{taskId} AND user_id = #{userId}
            """)
    LiepinJobTask findByIdAndUser(@Param("taskId") long taskId, @Param("userId") String userId);

    /** 查询用户最新任务。 */
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
