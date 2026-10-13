package com.snowworld.file;

import java.time.OffsetDateTime;
import java.util.List;
import lombok.Data;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FileMapper {

    @Data
    class FileRow {
        private Long id;
        private String objectKey;
        private String originalName;
        private String contentType;
        private Long sizeBytes;
        private String sha256;
        private Long siteId;
        private String bizType;
        private Long bizId;
        private Long uploaderId;
        private OffsetDateTime createdAt;
    }

    @Insert("""
            INSERT INTO file_asset (object_key, original_name, content_type, size_bytes, sha256, site_id, biz_type, biz_id, uploader_id)
            VALUES (#{objectKey}, #{originalName}, #{contentType}, #{sizeBytes}, #{sha256}, #{siteId}, #{bizType}, #{bizId}, #{uploaderId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insert(FileRow row);

    @Select("SELECT * FROM file_asset WHERE id = #{id}")
    FileRow findById(long id);

    @Select("""
            <script>
            SELECT * FROM file_asset WHERE id IN
            <foreach collection="ids" item="i" open="(" separator="," close=")">#{i}</foreach>
            ORDER BY id
            </script>
            """)
    List<FileRow> findByIds(@Param("ids") List<Long> ids);

    /** 文件被业务对象引用后记录归属, 已有归属的不覆盖。 */
    @Update("""
            <script>
            UPDATE file_asset SET biz_id = #{bizId} WHERE biz_id IS NULL AND id IN
            <foreach collection="ids" item="i" open="(" separator="," close=")">#{i}</foreach>
            </script>
            """)
    int bind(@Param("ids") List<Long> ids, @Param("bizId") long bizId);
}
