package com.texcellsms.sms.mapper.webportal;

import com.texcellsms.sms.model.entity.LoginAuditLog;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface LoginAuditLogMapper {

    /**
     * 插入登录日志
     */
    @Insert("INSERT INTO tbl_Login_Audit_Log (" +
            "logUuid, username, realIp, sourceDomain, loginTime, " +
            "loginResult, resultCode, resultMessage, " +
            "cacheDbWhitelistIp, cacheDbFaKey, cacheDbIsEnable, cacheDbRawResult, " +
            "requestMethod, requestUri, userAgent, referer, " +
            "cfConnectingIp, xRealIp, xForwardedFor, remoteAddr, " +
            "extInfo, remark" +
            ") VALUES (" +
            "#{logUuid}, #{username}, #{realIp}, #{sourceDomain}, #{loginTime}, " +
            "#{loginResult}, #{resultCode}, #{resultMessage}, " +
            "#{cacheDbWhitelistIp}, #{cacheDbFaKey}, #{cacheDbIsEnable}, #{cacheDbRawResult}, " +
            "#{requestMethod}, #{requestUri}, #{userAgent}, #{referer}, " +
            "#{cfConnectingIp}, #{xRealIp}, #{xForwardedFor}, #{remoteAddr}, " +
            "#{extInfo}, #{remark}" +
            ")")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(LoginAuditLog log);

    /**
     * 分页查询登录日志（支持模糊查询）
     */
    @Select("<script>" +
            "SELECT * FROM tbl_Login_Audit_Log " +
            "<where>" +
            "<if test='username != null and username != \"\"'>" +
            "AND username LIKE CONCAT('%', #{username}, '%') " +
            "</if>" +
            "<if test='realIp != null and realIp != \"\"'>" +
            "AND realIp LIKE CONCAT('%', #{realIp}, '%') " +  // 改为模糊查询
            "</if>" +
            "<if test='loginResult != null'>" +
            "AND loginResult = #{loginResult} " +
            "</if>" +
            "<if test='startTime != null'>" +
            "AND loginTime &gt;= #{startTime} " +
            "</if>" +
            "<if test='endTime != null'>" +
            "AND loginTime &lt;= #{endTime} " +
            "</if>" +
            "</where>" +
            "ORDER BY loginTime DESC " +
            "LIMIT #{offset}, #{limit}" +
            "</script>")
    List<LoginAuditLog> selectPage(@Param("offset") int offset,
                                   @Param("limit") int limit,
                                   @Param("username") String username,
                                   @Param("realIp") String realIp,
                                   @Param("loginResult") Integer loginResult,
                                   @Param("startTime") LocalDateTime startTime,
                                   @Param("endTime") LocalDateTime endTime);

    /**
     * 查询总数
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM tbl_Login_Audit_Log " +
            "<where>" +
            "<if test='username != null and username != \"\"'>" +
            "AND username LIKE CONCAT('%', #{username}, '%') " +
            "</if>" +
            "<if test='realIp != null and realIp != \"\"'>" +
            "AND realIp LIKE CONCAT('%', #{realIp}, '%') " +
            "</if>" +
            "<if test='loginResult != null'>" +
            "AND loginResult = #{loginResult} " +
            "</if>" +
            "<if test='startTime != null'>" +
            "AND loginTime &gt;= #{startTime} " +
            "</if>" +
            "<if test='endTime != null'>" +
            "AND loginTime &lt;= #{endTime} " +
            "</if>" +
            "</where>" +
            "</script>")
    long count(@Param("username") String username,
               @Param("realIp") String realIp,
               @Param("loginResult") Integer loginResult,
               @Param("startTime") LocalDateTime startTime,
               @Param("endTime") LocalDateTime endTime);

    /**
     * 获取失败次数最多的IP（暴力破解检测）-
     */
    @Select("SELECT realIp, COUNT(*) as failCount " +
            "FROM tbl_Login_Audit_Log " +
            "WHERE loginResult = 0 " +
            "AND loginTime >= #{startTime} " +
            "GROUP BY realIp " +
            "ORDER BY failCount DESC " +
            "LIMIT #{limit}")
    List<LoginAuditLog> selectTopFailIps(@Param("startTime") LocalDateTime startTime,
                                         @Param("limit") int limit);
}