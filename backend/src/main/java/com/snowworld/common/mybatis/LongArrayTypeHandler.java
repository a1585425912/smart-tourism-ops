package com.snowworld.common.mybatis;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/** Long[] <-> PostgreSQL BIGINT[]。 */
@MappedTypes(Long[].class)
public class LongArrayTypeHandler extends BaseTypeHandler<Long[]> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Long[] parameter, JdbcType jdbcType) throws SQLException {
        ps.setArray(i, ps.getConnection().createArrayOf("bigint", parameter));
    }

    @Override
    public Long[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return convert(rs.getArray(columnName));
    }

    @Override
    public Long[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return convert(rs.getArray(columnIndex));
    }

    @Override
    public Long[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return convert(cs.getArray(columnIndex));
    }

    private static Long[] convert(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        Object[] raw = (Object[]) array.getArray();
        Long[] out = new Long[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = raw[i] == null ? null : ((Number) raw[i]).longValue();
        }
        return out;
    }
}
