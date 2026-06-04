package com.texcellsms.sms.mapper.webportal;

import com.texcellsms.sms.model.entity.CustomerNumberFile;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface CustomerNumberFileMapper {

    @Insert("INSERT INTO tbl_Customer_Number_File (task_id, username, file_name, file_path, file_size, number_count, create_time) " +
            "VALUES (#{taskId}, #{username}, #{fileName}, #{filePath}, #{fileSize}, #{numberCount}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CustomerNumberFile record);

    // 模糊查询分页
    @Select("<script>" +
            "SELECT * FROM tbl_Customer_Number_File " +
            "<where>" +
            "<if test='username != null and username != \"\"'>AND username LIKE #{username}</if>" +
            "<if test='startTime != null'>AND create_time &gt;= #{startTime}</if>" +
            "<if test='endTime != null'>AND create_time &lt;= #{endTime}</if>" +
            "</where>" +
            "ORDER BY create_time DESC LIMIT #{offset}, #{limit}</script>")
    List<CustomerNumberFile> selectPageLike(@Param("offset") int offset,
                                            @Param("limit") int limit,
                                            @Param("username") String username,
                                            @Param("startTime") LocalDateTime startTime,
                                            @Param("endTime") LocalDateTime endTime);

    // 模糊查询计数
    @Select("<script>" +
            "SELECT COUNT(*) FROM tbl_Customer_Number_File " +
            "<where>" +
            "<if test='username != null and username != \"\"'>AND username LIKE #{username}</if>" +
            "<if test='startTime != null'>AND create_time &gt;= #{startTime}</if>" +
            "<if test='endTime != null'>AND create_time &lt;= #{endTime}</if>" +
            "</where></script>")
    long countForPageLike(@Param("username") String username,
                          @Param("startTime") LocalDateTime startTime,
                          @Param("endTime") LocalDateTime endTime);

    // 统计接口
    @Select("SELECT COUNT(*) as totalFiles, COUNT(DISTINCT username) as totalCustomers FROM tbl_Customer_Number_File")
    Map<String, Object> getStatistics();

    // 根据ID查询
    @Select("SELECT * FROM tbl_Customer_Number_File WHERE id = #{id}")
    CustomerNumberFile selectById(@Param("id") Long id);

    // 获取所有客户账号列表
    @Select("SELECT DISTINCT username FROM tbl_Customer_Number_File ORDER BY username")
    List<String> getAllUsernames();

    // 查询3天前的文件
    @Select("SELECT * FROM tbl_Customer_Number_File WHERE create_time < #{beforeTime}")
    List<CustomerNumberFile> selectByTimeBefore(@Param("beforeTime") LocalDateTime beforeTime);

    // 删除文件记录
    @Delete("DELETE FROM tbl_Customer_Number_File WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}