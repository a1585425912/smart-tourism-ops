package com.snowworld.scenic;

import java.util.List;
import lombok.Data;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** OpenStreetMap 兴趣点(ODbL), 只作为空间参考与业态点位底图。 */
@Mapper
public interface PoiMapper {

    @Data
    class PoiRow {
        private Long id;
        private Long siteId;
        private String osmRef;
        private String name;
        private String category;
        private String subType;
        private Double lon;
        private Double lat;
    }

    @Select("""
            <script>
            SELECT id, site_id, osm_ref, name, category, sub_type, ST_X(geom) AS lon, ST_Y(geom) AS lat FROM poi
            WHERE site_id IN <foreach collection="siteIds" item="s" open="(" separator="," close=")">#{s}</foreach>
            <if test="category != null"> AND category = #{category}</if>
            ORDER BY id
            </script>
            """)
    List<PoiRow> find(@Param("siteIds") List<Long> siteIds, @Param("category") String category);

    @Insert("""
            INSERT INTO poi (site_id, osm_ref, name, category, sub_type, geom)
            VALUES (#{siteId}, #{osmRef}, #{name}, #{category}, #{subType}, ST_SetSRID(ST_Point(#{lon}, #{lat}), 4326))
            ON CONFLICT (osm_ref) DO NOTHING
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(PoiRow row);

    /** 返回点位所在(含 buffer 米范围内)的景区, 不在任何景区附近返回 null。 */
    @Select("""
            SELECT id FROM scenic_site
            WHERE ST_DWithin(boundary::geography, ST_SetSRID(ST_Point(#{lon}, #{lat}), 4326)::geography, #{buffer})
            ORDER BY ST_Distance(boundary::geography, ST_SetSRID(ST_Point(#{lon}, #{lat}), 4326)::geography) LIMIT 1
            """)
    Long nearestSite(@Param("lon") double lon, @Param("lat") double lat, @Param("buffer") double bufferMeters);
}
