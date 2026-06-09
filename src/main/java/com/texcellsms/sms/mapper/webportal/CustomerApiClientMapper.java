package com.texcellsms.sms.mapper.webportal;

import com.texcellsms.sms.model.entity.CustomerApiClient;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface CustomerApiClientMapper {

    /**
     * 插入客户
     */
    @Insert("INSERT INTO tbl_api_client (username, password, status, remark) " +
            "VALUES (#{username}, #{password}, #{status}, #{remark})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CustomerApiClient client);

    /**
     * 更新客户
     */
    @Update("UPDATE tbl_api_client SET username=#{username}, password=#{password}, " +
            "status=#{status}, remark=#{remark} WHERE id=#{id}")
    int update(CustomerApiClient client);

    /**
     * 删除客户
     */
    @Delete("DELETE FROM tbl_api_client WHERE id=#{id}")
    int deleteById(@Param("id") Long id);

    /**
     * 根据ID查询客户
     */
    @Select("SELECT * FROM tbl_api_client WHERE id=#{id}")
    CustomerApiClient selectById(@Param("id") Long id);

    /**
     * 根据用户名查询客户
     */
    @Select("SELECT * FROM tbl_api_client WHERE username=#{username}")
    CustomerApiClient selectByUsername(@Param("username") String username);

    /**
     * 分页查询客户列表
     */
    @Select("<script>" +
            "SELECT * FROM tbl_api_client " +
            "<where>" +
            "<if test='username != null and username != \"\"'>AND username LIKE CONCAT('%', #{username}, '%')</if>" +
            "<if test='status != null'>AND status = #{status}</if>" +
            "</where>" +
            "ORDER BY create_time DESC LIMIT #{offset}, #{limit}</script>")
    List<CustomerApiClient> selectPage(@Param("offset") int offset,
                                       @Param("limit") int limit,
                                       @Param("username") String username,
                                       @Param("status") Integer status);

    /**
     * 分页查询计数
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM tbl_api_client " +
            "<where>" +
            "<if test='username != null and username != \"\"'>AND username LIKE CONCAT('%', #{username}, '%')</if>" +
            "<if test='status != null'>AND status = #{status}</if>" +
            "</where></script>")
    long countForPage(@Param("username") String username,
                      @Param("status") Integer status);

    /**
     * 查询所有启用的客户（用于API认证）
     */
    @Select("SELECT * FROM tbl_api_client WHERE status = 1 ORDER BY username")
    List<CustomerApiClient> selectAllActive();

    /**
     * 获取客户统计信息
     */
    @Select("SELECT COUNT(*) as total, " +
            "SUM(CASE WHEN status=1 THEN 1 ELSE 0 END) as active, " +
            "SUM(CASE WHEN status=0 THEN 1 ELSE 0 END) as disabled " +
            "FROM tbl_api_client")
    Map<String, Object> getStatistics();
}