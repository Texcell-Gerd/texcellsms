package com.texcellsms.sms.mapper.webportal;

import com.texcellsms.sms.model.entity.LoginAuditLog;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface LoginAuditLogMapper {

    // ====================== 日志插入 ======================
    @Insert("INSERT INTO tbl_Login_Audit_Log (" +
            "logUuid, username, realIp, ipCountry, ipRegion, ipCity, ipIsp, ipLocation, " +
            "sourceDomain, loginTime, loginResult, resultCode, resultMessage, " +
            "cacheDbWhitelistIp, cacheDbFaKey, cacheDbIsEnable, cacheDbRawResult, " +
            "authType, twoFactorType, twoFactorTarget, twoFaResult, loginScene, " +
            "requestMethod, requestUri, userAgent, referer, " +
            "cfConnectingIp, xRealIp, xForwardedFor, remoteAddr, loginDuration, " +
            "extInfo, remark, createTime, updateTime) " +
            "VALUES (" +
            "#{logUuid}, #{username}, #{realIp}, #{ipCountry}, #{ipRegion}, #{ipCity}, #{ipIsp}, #{ipLocation}, " +
            "#{sourceDomain}, #{loginTime}, #{loginResult}, #{resultCode}, #{resultMessage}, " +
            "#{cacheDbWhitelistIp}, #{cacheDbFaKey}, #{cacheDbIsEnable}, #{cacheDbRawResult}, " +
            "#{authType}, #{twoFactorType}, #{twoFactorTarget}, #{twoFaResult}, #{loginScene}, " +
            "#{requestMethod}, #{requestUri}, #{userAgent}, #{referer}, " +
            "#{cfConnectingIp}, #{xRealIp}, #{xForwardedFor}, #{remoteAddr}, #{loginDuration}, " +
            "#{extInfo}, #{remark}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(LoginAuditLog log);

    // ====================== 2FA 状态更新======================
    @Update("UPDATE tbl_Login_Audit_Log " +
            "SET twoFaResult = #{twoFaResult}, loginScene = #{loginScene}, " +
            "loginResult = CASE WHEN #{twoFaResult} = 1 THEN 1 ELSE 0 END, updateTime = NOW() " +
            "WHERE username = #{username} AND realIp = #{ip} AND loginScene = '2FA_REQUIRED' " +
            "ORDER BY loginTime DESC LIMIT 1")
    void update2FaResult(
            @Param("username") String username,
            @Param("ip") String ip,
            @Param("twoFaResult") Integer twoFaResult,
            @Param("loginScene") String loginScene
    );

    // ====================== 分页查询 ======================
    @Select("<script>" +
            "SELECT * FROM tbl_Login_Audit_Log " +
            "<where>" +
            "<if test='username != null and username != \"\"'>AND username LIKE CONCAT('%', #{username}, '%') </if>" +
            "<if test='realIp != null and realIp != \"\"'>AND realIp LIKE CONCAT('%', #{realIp}, '%') </if>" +
            "<if test='loginResult != null'>AND loginResult = #{loginResult} </if>" +
            "<if test='twoFactorType != null and twoFactorType != \"\"'>AND twoFactorType = #{twoFactorType} </if>" +
            "<if test='startTime != null'>AND loginTime &gt;= #{startTime} </if>" +
            "<if test='endTime != null'>AND loginTime &lt;= #{endTime} </if>" +
            "</where>" +
            "ORDER BY loginTime DESC LIMIT #{offset}, #{limit}</script>")
    List<LoginAuditLog> selectPage(@Param("offset") int offset,
                                   @Param("limit") int limit,
                                   @Param("username") String username,
                                   @Param("realIp") String realIp,
                                   @Param("loginResult") Integer loginResult,
                                   @Param("twoFactorType") String twoFactorType,
                                   @Param("startTime") LocalDateTime startTime,
                                   @Param("endTime") LocalDateTime endTime);

    @Select("<script>" +
            "SELECT COUNT(*) FROM tbl_Login_Audit_Log " +
            "<where>" +
            "<if test='username != null and username != \"\"'>AND username LIKE CONCAT('%', #{username}, '%') </if>" +
            "<if test='realIp != null and realIp != \"\"'>AND realIp LIKE CONCAT('%', #{realIp}, '%') </if>" +
            "<if test='loginResult != null'>AND loginResult = #{loginResult} </if>" +
            "<if test='twoFactorType != null and twoFactorType != \"\"'>AND twoFactorType = #{twoFactorType} </if>" +
            "<if test='startTime != null'>AND loginTime &gt;= #{startTime} </if>" +
            "<if test='endTime != null'>AND loginTime &lt;= #{endTime} </if>" +
            "</where></script>")
    long count(@Param("username") String username,
               @Param("realIp") String realIp,
               @Param("loginResult") Integer loginResult,
               @Param("twoFactorType") String twoFactorType,
               @Param("startTime") LocalDateTime startTime,
               @Param("endTime") LocalDateTime endTime);


    @Select("<script>" +
            "SELECT * FROM tbl_Login_Audit_Log " +
            "<where>" +
            "<if test='username != null and username != \"\"'>AND username LIKE CONCAT('%', #{username}, '%') </if>" +
            "<if test='realIp != null and realIp != \"\"'>AND realIp LIKE CONCAT('%', #{realIp}, '%') </if>" +
            "<if test='loginResult != null'>AND loginResult = #{loginResult} </if>" +
            "<if test='twoFactorType != null and twoFactorType != \"\"'>AND twoFactorType = #{twoFactorType} </if>" +
            "<if test='startTime != null'>AND loginTime &gt;= #{startTime} </if>" +
            "<if test='endTime != null'>AND loginTime &lt;= #{endTime} </if>" +
            "</where>" +
            "ORDER BY loginTime DESC</script>")
    List<LoginAuditLog> selectForExport(@Param("username") String username,
                                        @Param("realIp") String realIp,
                                        @Param("loginResult") Integer loginResult,
                                        @Param("twoFactorType") String twoFactorType,
                                        @Param("startTime") LocalDateTime startTime,
                                        @Param("endTime") LocalDateTime endTime);

    // ====================== 统计接口 ======================
    @Select("SELECT COUNT(*) as totalCount, SUM(CASE WHEN loginResult = 1 THEN 1 ELSE 0 END) as successCount, " +
            "SUM(CASE WHEN loginResult = 0 THEN 1 ELSE 0 END) as failCount " +
            "FROM tbl_Login_Audit_Log WHERE loginTime >= #{startTime} AND loginTime <= #{endTime}")
    Map<String, Object> getStatsByTimeRange(@Param("startTime") LocalDateTime startTime,
                                            @Param("endTime") LocalDateTime endTime);

    @Select("SELECT twoFactorType, COUNT(*) as totalCount, " +
            "SUM(CASE WHEN twoFaResult = 1 THEN 1 ELSE 0 END) as successCount " +
            "FROM tbl_Login_Audit_Log WHERE twoFactorType IS NOT NULL AND loginTime >= #{startTime} GROUP BY twoFactorType")
    List<Map<String, Object>> getTwoFactorStats(@Param("startTime") LocalDateTime startTime);

    @Select("SELECT realIp, COUNT(*) as failCount FROM tbl_Login_Audit_Log " +
            "WHERE loginResult = 0 AND loginTime >= #{startTime} GROUP BY realIp ORDER BY failCount DESC LIMIT #{limit}")
    List<Map<String, Object>> getTopFailIps(@Param("startTime") LocalDateTime startTime,
                                            @Param("limit") int limit);

    @Select("SELECT username, COUNT(*) as failCount FROM tbl_Login_Audit_Log " +
            "WHERE loginResult = 0 AND loginTime >= #{startTime} GROUP BY username ORDER BY failCount DESC LIMIT #{limit}")
    List<Map<String, Object>> getTopFailUsernames(@Param("startTime") LocalDateTime startTime,
                                                  @Param("limit") int limit);

    @Select("SELECT HOUR(loginTime) as hour, COUNT(*) as totalCount, " +
            "SUM(CASE WHEN loginResult = 1 THEN 1 ELSE 0 END) as successCount, " +
            "SUM(CASE WHEN loginResult = 0 THEN 1 ELSE 0 END) as failCount " +
            "FROM tbl_Login_Audit_Log WHERE DATE(loginTime) = CURDATE() GROUP BY HOUR(loginTime) ORDER BY hour")
    List<Map<String, Object>> getTodayHourlyDistribution();

    @Select("SELECT COUNT(DISTINCT username) FROM tbl_Login_Audit_Log WHERE DATE(loginTime) = CURDATE() AND loginResult = 1")
    int getTodayActiveUsers();

    @Select("SELECT realIp, COUNT(*) as failCount FROM tbl_Login_Audit_Log " +
            "WHERE loginResult = 0 AND DATE(loginTime) = CURDATE() GROUP BY realIp ORDER BY failCount DESC LIMIT 5")
    List<Map<String, Object>> getTodayTopFailIps();

    @Select("SELECT twoFactorType, COUNT(*) as totalCount, " +
            "SUM(CASE WHEN twoFaResult = 1 THEN 1 ELSE 0 END) as successCount " +
            "FROM tbl_Login_Audit_Log WHERE DATE(loginTime) = CURDATE() AND twoFactorType IS NOT NULL GROUP BY twoFactorType")
    List<Map<String, Object>> getTodayTwoFactorStats();

    @Select("SELECT DISTINCT username FROM tbl_Login_Audit_Log WHERE DATE(loginTime) = CURDATE() AND loginResult = 1 ORDER BY username")
    List<String> getTodayActiveUserList();

    @Select("SELECT * FROM tbl_Login_Audit_Log WHERE id = #{id}")
    LoginAuditLog selectById(@Param("id") Long id);

    @Delete("DELETE FROM tbl_Login_Audit_Log WHERE loginTime < #{beforeTime}")
    int deleteByTime(@Param("beforeTime") LocalDateTime beforeTime);
}