package com.texcellsms.sms.config;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 全局处理VARCHAR类型的yyyy/MM/dd HH:mm:ss格式字符串转Date
 */
@MappedTypes(Date.class) // 映射DTO的Date类型
@MappedJdbcTypes(JdbcType.VARCHAR) // 映射数据库的VARCHAR类型
public class VarcharDateTypeHandler extends BaseTypeHandler<Date> {

    // 支持多种时间格式（防止格式不一致）
    private static final SimpleDateFormat[] FORMATS = {
            new SimpleDateFormat("yyyy/MM/dd HH:mm:ss"),
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"),
            new SimpleDateFormat("yyyyMMdd HH:mm:ss")
    };

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Date parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, FORMATS[0].format(parameter));
    }

    @Override
    public Date getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parseDate(rs.getString(columnName));
    }

    @Override
    public Date getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parseDate(rs.getString(columnIndex));
    }

    @Override
    public Date getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parseDate(cs.getString(columnIndex));
    }

    // 尝试多种格式解析
    private Date parseDate(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        for (SimpleDateFormat format : FORMATS) {
            try {
                return format.parse(value);
            } catch (ParseException e) {
                continue; // 格式不匹配，尝试下一种
            }
        }
        throw new RuntimeException("无法解析时间字符串：" + value);
    }
}