package ykd.ykd.job.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import ykd.ykd.job.model.LiepinResumeAsset;

@Mapper
public interface LiepinResumeAssetMapper {
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

    @Delete("DELETE FROM liepin_resume_asset WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") String userId);

    @Select("""
            SELECT id, user_id, file_name, file_path, file_type, file_size, file_hash,
                   created_at, updated_at
            FROM liepin_resume_asset
            WHERE user_id = #{userId}
            """)
    LiepinResumeAsset findByUserId(@Param("userId") String userId);
}