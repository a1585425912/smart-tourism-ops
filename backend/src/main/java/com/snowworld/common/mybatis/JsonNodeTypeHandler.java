package com.snowworld.common.mybatis;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** JsonNode <-> PostgreSQL JSONB。 */
@MappedTypes(JsonNode.class)
public class JsonNodeTypeHandler extends BaseTypeHandler<JsonNode> {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, JsonNode parameter, JdbcType jdbcType) throws SQLException {
        // Types.OTHER 让驱动以未指定类型发送, 由 PostgreSQL 隐式转换为 jsonb
        ps.setObject(i, MAPPER.writeValueAsString(parameter), Types.OTHER);
    }

    @Override
    public JsonNode getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public JsonNode getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public JsonNode getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private static JsonNode parse(String value) {
        return value == null ? null : MAPPER.readTree(value);
    }
}
