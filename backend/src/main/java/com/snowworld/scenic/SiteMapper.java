package com.snowworld.scenic;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonRawValue;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SiteMapper {

    @Data
    class SiteRow {
        private Long id;
        private String code;
        private String name;
        private String shortName;
        private String timezone;
        private Integer capacity;
        private BigDecimal warnRatio;
        private LocalTime openTime;
        private LocalTime closeTime;
        private String seasonNote;
        private String description;
        /** GeoJSON Point */
        @JsonRawValue
        private String center;
        /** GeoJSON MultiPolygon */
        @JsonRawValue
        private String boundary;
        private String boundarySrc;
        private String status;
        private Integer sortOrder;
        private Double areaSqm;
    }

    String COLUMNS = """
            id, code, name, short_name, timezone, capacity, warn_ratio, open_time, close_time, season_note, description,
            ST_AsGeoJSON(center, 7) AS center, ST_AsGeoJSON(boundary, 7) AS boundary, boundary_src, status, sort_order,
            ST_Area(boundary::geography) AS area_sqm
            """;

    @Select("SELECT " + COLUMNS + " FROM scenic_site ORDER BY sort_order, id")
    List<SiteRow> findAll();

    @Select("SELECT " + COLUMNS + " FROM scenic_site WHERE id = #{id}")
    SiteRow findById(long id);

    @Select("SELECT " + COLUMNS + " FROM scenic_site WHERE code = #{code}")
    SiteRow findByCode(String code);

    @Insert("""
            INSERT INTO scenic_site (code, name, short_name, capacity, warn_ratio, open_time, close_time, season_note,
                description, center, boundary, boundary_src, status, sort_order)
            VALUES (#{code}, #{name}, #{shortName}, #{capacity}, #{warnRatio}, #{openTime}, #{closeTime}, #{seasonNote},
                #{description}, ST_SetSRID(ST_GeomFromGeoJSON(#{center}), 4326),
                ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(#{boundary}), 4326)), #{boundarySrc}, #{status}, #{sortOrder})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insert(SiteRow row);

    @Update("""
            UPDATE scenic_site SET name = #{name}, short_name = #{shortName}, capacity = #{capacity}, warn_ratio = #{warnRatio},
                open_time = #{openTime}, close_time = #{closeTime}, season_note = #{seasonNote}, description = #{description},
                status = #{status}, updated_at = now()
            WHERE id = #{id}
            """)
    int update(SiteRow row);

    @Select("SELECT count(*) FROM scenic_site")
    long count();

    /** 校验 GeoJSON: 返回 null 表示合法, 否则返回原因。 */
    @Select("""
            SELECT CASE
                WHEN GeometryType(g) NOT IN ('POLYGON') THEN '必须是单个多边形'
                WHEN NOT ST_IsValid(g) THEN ST_IsValidReason(g)
                WHEN ST_Area(g::geography) < 50 THEN '面积过小(小于50平方米)'
                WHEN ST_XMin(g) < 73 OR ST_XMax(g) > 136 OR ST_YMin(g) < 3 OR ST_YMax(g) > 54 THEN '坐标超出中国范围, 请确认使用 WGS84 经纬度'
                ELSE NULL END
            FROM (SELECT ST_SetSRID(ST_GeomFromGeoJSON(#{geojson}), 4326) AS g) t
            """)
    String validatePolygon(@Param("geojson") String geojson);
}
