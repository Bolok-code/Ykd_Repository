package ykd.ykd.job.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import ykd.ykd.job.model.LiepinResume;

/**
 * 简历内容 Mapper，操作表 liepin_resume。
 *
 * <p>每个用户一条简历记录，upsert 保证唯一性。
 * 仅存储纯文本内容（供 AI 匹配），附件信息在 {@link LiepinResumeAssetMapper} 中独立管理。</p>
 */
@Mapper
public interface LiepinResumeMapper {

    /**
     * 插入或更新简历纯文本。
     * {@code ON CONFLICT(user_id) DO UPDATE} — 同一用户重复上传时覆盖旧内容。
     */
    @Insert("""
            INSERT INTO liepin_resume(user_id, file_name, content, updated_at)
            VALUES(#{userId}, #{fileName}, #{content}, CURRENT_TIMESTAMP)
            ON CONFLICT(user_id) DO UPDATE SET
                file_name = excluded.file_name,
                content = excluded.content,
                updated_at = CURRENT_TIMESTAMP
            """)
    int upsert(LiepinResume resume);

    /** 查询用户简历。 */
    @Select("""
            SELECT id, user_id, file_name, content, updated_at
            FROM liepin_resume
            WHERE user_id = #{userId}
            """)
    LiepinResume findByUserId(@Param("userId") String userId);
}
