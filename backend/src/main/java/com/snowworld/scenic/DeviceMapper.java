package com.snowworld.scenic;

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
public interface DeviceMapper {

    @Data
    class DeviceRow {
        private Long id;
        private Long siteId;
        private Long zoneId;
        private String zoneName;
        private String code;
        private String name;
        private String deviceType;
        private Boolean keyPoint;
        private Double lon;
        private Double lat;
        private String vendor;
        private String protocol;
        private String sourceDeviceId;
        private String streamRef;
        private String status;
        private OffsetDateTime statusAt;
        private String dataMode;
    }

    String COLUMNS = """
            d.id, d.site_id, d.zone_id, z.name AS zone_name, d.code, d.name, d.device_type, d.key_point,
            ST_X(d.location) AS lon, ST_Y(d.location) AS lat, d.vendor, d.protocol, d.source_device_id, d.stream_ref,
            d.status, d.status_at, d.data_mode
            """;

    @Select("""
            <script>
            SELECT """ + COLUMNS + """
            FROM device d LEFT JOIN scenic_zone z ON z.id = d.zone_id
            WHERE d.site_id IN <foreach collection="siteIds" item="s" open="(" separator="," close=")">#{s}</foreach>
            <if test="type != null"> AND d.device_type = #{type}</if>
            <if test="status != null"> AND d.status = #{status}</if>
            <if test="keyPoint != null"> AND d.key_point = #{keyPoint}</if>
            ORDER BY d.site_id, d.key_point DESC, d.id
            </script>
            """)
    List<DeviceRow> find(@Param("siteIds") List<Long> siteIds, @Param("type") String type,
                         @Param("status") String status, @Param("keyPoint") Boolean keyPoint);

    @Select("SELECT " + COLUMNS + " FROM device d LEFT JOIN scenic_zone z ON z.id = d.zone_id WHERE d.id = #{id}")
    DeviceRow findById(long id);

    @Insert("""
            INSERT INTO device (site_id, zone_id, code, name, device_type, key_point, location, vendor, protocol,
                source_device_id, stream_ref, status, status_at, data_mode)
            VALUES (#{siteId}, #{zoneId}, #{code}, #{name}, #{deviceType}, #{keyPoint}, ST_SetSRID(ST_Point(#{lon}, #{lat}), 4326),
                #{vendor}, #{protocol}, #{sourceDeviceId}, #{streamRef}, #{status}, #{statusAt}, #{dataMode})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insert(DeviceRow row);

    @Update("UPDATE device SET status = #{status}, status_at = #{at}, updated_at = now() WHERE id = #{id}")
    int updateStatus(@Param("id") long id, @Param("status") String status, @Param("at") OffsetDateTime at);

    @Update("UPDATE device SET zone_id = #{zoneId}, updated_at = now() WHERE id = #{id}")
    int updateZone(@Param("id") long id, @Param("zoneId") Long zoneId);

    @Select("""
            <script>
            SELECT d.site_id AS site_id, d.device_type AS device_type, d.status AS status, count(*) AS cnt, max(d.status_at) AS latest
            FROM device d
            WHERE d.site_id IN <foreach collection="siteIds" item="s" open="(" separator="," close=")">#{s}</foreach>
            GROUP BY d.site_id, d.device_type, d.status
            ORDER BY d.site_id, d.device_type, d.status
            </script>
            """)
    List<StatusCount> statusSummary(@Param("siteIds") List<Long> siteIds);

    @Data
    class StatusCount {
        private Long siteId;
        private String deviceType;
        private String status;
        private Integer cnt;
        private OffsetDateTime latest;
    }
}
