package com.texcellsms.sms.mapper.webportal;

import com.texcellsms.sms.model.entity.OpsAuditLog;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface OpsAuditLogMapper {

    @Insert("INSERT INTO tbl_ops_audit_log (operator, operation_type, operation_name, target, result, result_msg, request_ip, user_agent) " +
            "VALUES (#{operator}, #{operationType}, #{operationName}, #{target}, #{result}, #{resultMsg}, #{requestIp}, #{userAgent})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OpsAuditLog log);

    @Select("<script>" +
            "SELECT * FROM tbl_ops_audit_log " +
            "<where>" +
            "<if test='operator != null and operator != \"\"'>AND operator LIKE CONCAT('%', #{operator}, '%')</if>" +
            "<if test='operationType != null and operationType != \"\"'>AND operation_type = #{operationType}</if>" +
            "<if test='startTime != null'>AND create_time >= #{startTime}</if>" +
            "<if test='endTime != null'>AND create_time &lt;= #{endTime}</if>" +
            "</where>" +
            "ORDER BY create_time DESC LIMIT #{offset}, #{limit}</script>")
    List<OpsAuditLog> selectPage(@Param("offset") int offset,
                                 @Param("limit") int limit,
                                 @Param("operator") String operator,
                                 @Param("operationType") String operationType,
                                 @Param("startTime") LocalDateTime startTime,
                                 @Param("endTime") LocalDateTime endTime);

    @Select("<script>" +
            "SELECT COUNT(*) FROM tbl_ops_audit_log " +
            "<where>" +
            "<if test='operator != null and operator != \"\"'>AND operator LIKE CONCAT('%', #{operator}, '%')</if>" +
            "<if test='operationType != null and operationType != \"\"'>AND operation_type = #{operationType}</if>" +
            "<if test='startTime != null'>AND create_time >= #{startTime}</if>" +
            "<if test='endTime != null'>AND create_time &lt;= #{endTime}</if>" +
            "</where></script>")
    long count(@Param("operator") String operator,
               @Param("operationType") String operationType,
               @Param("startTime") LocalDateTime startTime,
               @Param("endTime") LocalDateTime endTime);

    // 统计本周和上周操作数量对比
    @Select("SELECT " +
            "COUNT(CASE WHEN DATE(create_time) >= CURDATE() - INTERVAL 7 DAY THEN 1 END) AS this_week_count, " +
            "COUNT(CASE WHEN DATE(create_time) >= CURDATE() - INTERVAL 14 DAY AND DATE(create_time) < CURDATE() - INTERVAL 7 DAY THEN 1 END) AS last_week_count " +
            "FROM tbl_ops_audit_log")
    Map<String, Object> getWeekCompareStats();

    // 本月操作统计（按类型）
    @Select("SELECT operation_type, operation_name, COUNT(*) as count " +
            "FROM tbl_ops_audit_log " +
            "WHERE MONTH(create_time) = MONTH(CURDATE()) AND YEAR(create_time) = YEAR(CURDATE()) " +
            "GROUP BY operation_type, operation_name ORDER BY count DESC")
    List<Map<String, Object>> getCurrentMonthStatsByType();

    // 操作人操作次数统计（本月）
    @Select("SELECT operator, COUNT(*) as total_count, " +
            "SUM(CASE WHEN result = 1 THEN 1 ELSE 0 END) as success_count, " +
            "SUM(CASE WHEN result = 0 THEN 1 ELSE 0 END) as fail_count " +
            "FROM tbl_ops_audit_log " +
            "WHERE MONTH(create_time) = MONTH(CURDATE()) AND YEAR(create_time) = YEAR(CURDATE()) " +
            "GROUP BY operator ORDER BY total_count DESC LIMIT 10")
    List<Map<String, Object>> getCurrentMonthStatsByOperator();

    // 近7天每日操作趋势
    @Select("SELECT DATE(create_time) as date, COUNT(*) as count " +
            "FROM tbl_ops_audit_log " +
            "WHERE create_time >= DATE_SUB(CURDATE(), INTERVAL 7 DAY) " +
            "GROUP BY DATE(create_time) ORDER BY date")
    List<Map<String, Object>> getLast7DaysTrend();
}