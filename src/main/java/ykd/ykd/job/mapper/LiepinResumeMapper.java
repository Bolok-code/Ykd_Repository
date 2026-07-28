package ykd.ykd.job.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import ykd.ykd.job.model.LiepinResume;

@Mapper
public interface LiepinResumeMapper {
    @Insert("""
            INSERT INTO liepin_resume(user_id, file_name, content, updated_at)
            VALUES(#{userId}, #{fileName}, #{content}, CURRENT_TIMESTAMP)
            ON CONFLICT(user_id) DO UPDATE SET
                file_name = excluded.file_name,
                content = excluded.content,
                updated_at = CURRENT_TIMESTAMP
            """)
    int upsert(LiepinResume resume);

    @Select("""
            SELECT id, user_id, file_name, content, updated_at
            FROM liepin_resume
            WHERE user_id = #{userId}
            """)
    LiepinResume findByUserId(@Param("userId") String userId);
}