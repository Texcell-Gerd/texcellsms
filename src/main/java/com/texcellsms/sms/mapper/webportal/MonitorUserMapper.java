package com.texcellsms.sms.mapper.webportal;

import com.texcellsms.sms.model.entity.MonitorUser;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface MonitorUserMapper {

    /**
     * 插入监控用户
     */
    @Insert("INSERT INTO tbl_monitor_user (username, password, role, status, allowed_ips, remark) " +
            "VALUES (#{username}, #{password}, #{role}, #{status}, #{allowedIps}, #{remark})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MonitorUser user);

    /**
     * 更新监控用户
     */
    @Update("UPDATE tbl_monitor_user SET username=#{username}, password=#{password}, " +
            "role=#{role}, status=#{status}, allowed_ips=#{allowedIps}, remark=#{remark} WHERE id=#{id}")
    int update(MonitorUser user);

    /**
     * 删除监控用户
     */
    @Delete("DELETE FROM tbl_monitor_user WHERE id=#{id}")
    int deleteById(@Param("id") Long id);

    /**
     * 根据ID查询监控用户
     */
    @Select("SELECT * FROM tbl_monitor_user WHERE id=#{id}")
    MonitorUser selectById(@Param("id") Long id);

    /**
     * 根据用户名查询监控用户
     */
    @Select("SELECT * FROM tbl_monitor_user WHERE username=#{username}")
    MonitorUser selectByUsername(@Param("username") String username);

    /**
     * 分页查询监控用户列表
     */
    @Select("<script>" +
            "SELECT * FROM tbl_monitor_user " +
            "<where>" +
            "<if test='username != null and username != \"\"'>AND username LIKE CONCAT('%', #{username}, '%')</if>" +
            "<if test='status != null'>AND status = #{status}</if>" +
            "</where>" +
            "ORDER BY create_time DESC LIMIT #{offset}, #{limit}</script>")
    List<MonitorUser> selectPage(@Param("offset") int offset,
                                 @Param("limit") int limit,
                                 @Param("username") String username,
                                 @Param("status") Integer status);

    /**
     * 分页查询计数
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM tbl_monitor_user " +
            "<where>" +
            "<if test='username != null and username != \"\"'>AND username LIKE CONCAT('%', #{username}, '%')</if>" +
            "<if test='status != null'>AND status = #{status}</if>" +
            "</where></script>")
    long countForPage(@Param("username") String username,
                      @Param("status") Integer status);

    /**
     * 查询所有启用的监控用户
     */
    @Select("SELECT * FROM tbl_monitor_user WHERE status = 1 ORDER BY username")
    List<MonitorUser> selectAllActive();
}