package com.snowworld.scenic;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonRawValue;
import lombok.Data;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ZoneMapper {

    @Data
    class ZoneRow {
        private Long id;
        private Long siteId;
        private String code;
        private String name;
        private String zoneType;
        /** GeoJSON Polygon, WGS84 */
        @JsonRawValue
        private String boundary;
        private Integer capacity;
        private BigDecimal warnRatio;
        private String countSource;
        private String remark;
        private Integer version;
        private Boolean active;
        private Double areaSqm;
        private Boolean outsideSite;
        private OffsetDateTime updatedAt;
    }

    String COLUMNS = """
            z.id, z.site_id, z.code, z.name, z.zone_type, ST_AsGeoJSON(z.boundary, 7) AS boundary, z.capacity, z.warn_ratio,
            z.count_source, z.remark, z.version, z.active, ST_Area(z.boundary::geography) AS area_sqm,
            (s.boundary IS NOT NULL AND NOT ST_Covers(ST_Buffer(s.boundary::geography, 30)::geometry, z.boundary)) AS outside_site,
            z.updated_at
            """;

    @Select("SELECT " + COLUMNS + " FROM scenic_zone z JOIN scenic_site s ON s.id = z.site_id WHERE z.site_id = #{siteId} AND z.active ORDER BY z.id")
    List<ZoneRow> findBySite(long siteId);

    @Select("SELECT " + COLUMNS + " FROM scenic_zone z JOIN scenic_site s ON s.id = z.site_id WHERE z.id = #{id}")
    ZoneRow findById(long id);

    @Insert("""
            INSERT INTO scenic_zone (site_id, code, name, zone_type, boundary, capacity, warn_ratio, count_source, remark, created_by)
            VALUES (#{row.siteId}, #{row.code}, #{row.name}, #{row.zoneType}, ST_SetSRID(ST_GeomFromGeoJSON(#{row.boundary}), 4326),
                    #{row.capacity}, #{row.warnRatio}, #{row.countSource}, #{row.remark}, #{userId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id", keyColumn = "id")
    void insert(@Param("row") ZoneRow row, @Param("userId") Long userId);

    /** 乐观锁更新, 返回 0 表示版本冲突。 */
    @Update("""
            UPDATE scenic_zone SET name = #{name}, zone_type = #{zoneType},
                boundary = ST_SetSRID(ST_GeomFromGeoJSON(#{boundary}), 4326), capacity = #{capacity}, warn_ratio = #{warnRatio},
                count_source = #{countSource}, remark = #{remark}, version = version + 1, updated_at = now()
            WHERE id = #{id} AND version = #{version}
            """)
    int update(ZoneRow row);

    @Update("UPDATE scenic_zone SET active = FALSE, version = version + 1, updated_at = now() WHERE id = #{id}")
    int deactivate(long id);

    @Select("SELECT id FROM scenic_zone WHERE site_id = #{siteId} AND active AND ST_Contains(boundary, ST_SetSRID(ST_Point(#{lon}, #{lat}), 4326)) ORDER BY ST_Area(boundary) LIMIT 1")
    Long locate(@Param("siteId") long siteId, @Param("lon") double lon, @Param("lat") double lat);
}
