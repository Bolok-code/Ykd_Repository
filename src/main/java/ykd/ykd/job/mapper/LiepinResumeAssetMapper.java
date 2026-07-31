package ykd.ykd.job.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import ykd.ykd.job.model.LiepinResumeAsset;

/**
 * 简历附件 Mapper，操作表 liepin_resume_asset。
 *
 * <p>每个用户一条附件记录，upsert 保证唯一性。存储原始简历文件的磁盘路径和元信息。</p>
 */
@Mapper
public interface LiepinResumeAssetMapper {

    /**
     * 插入或更新简历附件元信息。
     * {@code ON CONFLICT(user_id) DO UPDATE} — 同一用户重复上传时覆盖旧记录。
     */
    @Insert("""
            INSERT INTO liepin_resume_asset(
                user_id, file_name, file_path, file_type, file_size, file_hash,
                created_at, updated_at)
            VALUES(
                #{userId}, #{fileName}, #{filePath}, #{fileType}, #{fileSize}, #{fileHash},
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT(user_id) DO UPDATE SET
                file_name = excluded.file_name,
                file_path = excluded.file_path,
                file_type = excluded.file_type,
                file_size = excluded.file_size,
                file_hash = excluded.file_hash,
                updated_at = CURRENT_TIMESTAMP
            """)
    int upsert(LiepinResumeAsset asset);

    /** 删除用户附件记录（更换文件时调用）。 */
    @Delete("DELETE FROM liepin_resume_asset WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") String userId);

    /** 查询用户附件元信息。 */
    @Select("""
            SELECT id, user_id, file_name, file_path, file_type, file_size, file_hash,
                   created_at, updated_at
            FROM liepin_resume_asset
            WHERE user_id = #{userId}
            """)
    LiepinResumeAsset findByUserId(@Param("userId") String userId);
}
