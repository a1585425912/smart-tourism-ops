package com.snowworld.scenic;

import com.snowworld.common.ApiException;
import com.snowworld.common.ErrorCode;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** GeoJSON 工具。几何对象只接受 type/coordinates, 丢弃其它字段, 由 PostGIS 负责合法性校验。 */
public final class GeoJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private GeoJson() {
    }

    public static String write(Map<String, Object> geometry) {
        Object type = geometry.get("type");
        Object coordinates = geometry.get("coordinates");
        if (!(type instanceof String) || coordinates == null) {
            throw new ApiException(ErrorCode.INVALID_GEOMETRY, "GeoJSON 必须包含 type 与 coordinates");
        }
        return MAPPER.writeValueAsString(Map.of("type", type, "coordinates", coordinates));
    }
}
