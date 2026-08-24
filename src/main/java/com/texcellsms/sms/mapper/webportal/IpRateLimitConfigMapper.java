package com.texcellsms.sms.mapper.webportal;

import com.texcellsms.sms.model.entity.IpRateLimitConfig;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface IpRateLimitConfigMapper {

    @Select("SELECT * FROM ip_rate_limit_config ORDER BY priority DESC, id ASC")
    List<IpRateLimitConfig> selectAll();

    @Select("SELECT * FROM ip_rate_limit_config WHERE enabled = 1 ORDER BY priority DESC")
    List<IpRateLimitConfig> selectEnabled();

    @Select("SELECT * FROM ip_rate_limit_config WHERE id = #{id}")
    IpRateLimitConfig selectById(@Param("id") Long id);

    @Select("SELECT * FROM ip_rate_limit_config WHERE ip = #{ip}")
    IpRateLimitConfig selectByIp(@Param("ip") String ip);

    @Insert("INSERT INTO ip_rate_limit_config (ip, customer_name, rate_limit, block_duration, enabled, priority, remark, create_by, update_by) " +
            "VALUES (#{ip}, #{customerName}, #{rateLimit}, #{blockDuration}, #{enabled}, #{priority}, #{remark}, #{createBy}, #{updateBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(IpRateLimitConfig config);

    @Update("UPDATE ip_rate_limit_config SET " +
            "ip = #{ip}, " +
            "customer_name = #{customerName}, " +
            "rate_limit = #{rateLimit}, " +
            "block_duration = #{blockDuration}, " +
            "enabled = #{enabled}, " +
            "priority = #{priority}, " +
            "remark = #{remark}, " +
            "update_by = #{updateBy} " +
            "WHERE id = #{id}")
    int update(IpRateLimitConfig config);

    @Delete("DELETE FROM ip_rate_limit_config WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    @Update("UPDATE ip_rate_limit_config SET enabled = #{enabled}, update_by = #{updateBy} WHERE id = #{id}")
    int toggleEnabled(@Param("id") Long id, @Param("enabled") Integer enabled, @Param("updateBy") String updateBy);

    @Select("SELECT COUNT(*) FROM ip_rate_limit_config")
    int count();
}