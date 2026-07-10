package com.texcellsms.sms.mapper.webportal;

import com.texcellsms.sms.model.entity.QueueMonitorLog;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface QueueMonitorLogMapper {

    /**
     * 插入告警日志
     */
    @Insert("INSERT INTO tbl_queue_monitor_log (task_id, sub_account, task_name, total_count, " +
            "submit_succ_count, dr_succ_count, dr_fail_count, pending_count, detail_pending_count, " +
            "task_time, elapsed_minutes, alert_level, alert_sent, status) " +
            "VALUES (#{taskId}, #{subAccount}, #{taskName}, #{totalCount}, #{submitSuccCount}, " +
            "#{drSuccCount}, #{drFailCount}, #{pendingCount}, #{detailPendingCount}, " +
            "#{taskTime}, #{elapsedMinutes}, #{alertLevel}, #{alertSent}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(QueueMonitorLog log);

    /**
     * 更新告警日志（用于定时更新已过时间和DR未更新数）
     */
    @Update("UPDATE tbl_queue_monitor_log SET " +
            "total_count = #{totalCount}, " +
            "submit_succ_count = #{submitSuccCount}, " +
            "dr_succ_count = #{drSuccCount}, " +
            "dr_fail_count = #{drFailCount}, " +
            "pending_count = #{pendingCount}, " +
            "detail_pending_count = #{detailPendingCount}, " +
            "task_time = #{taskTime}, " +
            "elapsed_minutes = #{elapsedMinutes}, " +
            "alert_level = #{alertLevel}, " +
            "update_time = NOW() " +
            "WHERE id = #{id}")
    int update(QueueMonitorLog log);

    /**
     * 标记告警已发送
     */
    @Update("UPDATE tbl_queue_monitor_log SET alert_sent = 1 WHERE task_id = #{taskId}")
    int updateAlertSent(@Param("taskId") String taskId);

    /**
     * 更新告警状态（处理/忽略）
     */
    @Update("UPDATE tbl_queue_monitor_log SET status = #{status}, processed_by = #{processedBy}, " +
            "processed_time = #{processedTime} WHERE task_id = #{taskId}")
    int updateStatus(@Param("taskId") String taskId,
                     @Param("status") String status,
                     @Param("processedBy") String processedBy,
                     @Param("processedTime") LocalDateTime processedTime);

    /**
     * 查询所有待处理的告警（status = PENDING）
     * 用于定时任务检测哪些任务已恢复
     */
    @Select("SELECT * FROM tbl_queue_monitor_log WHERE status = 'PENDING' ORDER BY create_time DESC")
    List<QueueMonitorLog> findPendingAlerts();

    /**
     * 根据任务ID查询最新一条记录
     */
    @Select("SELECT * FROM tbl_queue_monitor_log WHERE task_id = #{taskId} ORDER BY create_time DESC LIMIT 1")
    QueueMonitorLog selectLatestByTaskId(@Param("taskId") String taskId);

    /**
     * 统计待处理告警数量（status = PENDING）
     */
    @Select("SELECT COUNT(*) FROM tbl_queue_monitor_log WHERE status = 'PENDING'")
    int countPendingAlerts();

    /**
     * 分页查询告警日志
     */
    @Select("<script>" +
            "SELECT * FROM tbl_queue_monitor_log " +
            "<where>" +
            "<if test='taskId != null and taskId != \"\"'>AND task_id LIKE CONCAT('%', #{taskId}, '%')</if>" +
            "<if test='subAccount != null and subAccount != \"\"'>AND sub_account LIKE CONCAT('%', #{subAccount}, '%')</if>" +
            "<if test='status != null and status != \"\"'>AND status = #{status}</if>" +
            "<if test='alertLevel != null and alertLevel != \"\"'>AND alert_level = #{alertLevel}</if>" +
            "<if test='startTime != null'>AND create_time >= #{startTime}</if>" +
            "<if test='endTime != null'>AND create_time &lt;= #{endTime}</if>" +
            "</where>" +
            "ORDER BY create_time DESC LIMIT #{offset}, #{limit}</script>")
    List<QueueMonitorLog> selectPage(@Param("offset") int offset,
                                     @Param("limit") int limit,
                                     @Param("taskId") String taskId,
                                     @Param("subAccount") String subAccount,
                                     @Param("status") String status,
                                     @Param("alertLevel") String alertLevel,
                                     @Param("startTime") LocalDateTime startTime,
                                     @Param("endTime") LocalDateTime endTime);

    /**
     * 统计告警日志数量
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM tbl_queue_monitor_log " +
            "<where>" +
            "<if test='taskId != null and taskId != \"\"'>AND task_id LIKE CONCAT('%', #{taskId}, '%')</if>" +
            "<if test='subAccount != null and subAccount != \"\"'>AND sub_account LIKE CONCAT('%', #{subAccount}, '%')</if>" +
            "<if test='status != null and status != \"\"'>AND status = #{status}</if>" +
            "<if test='alertLevel != null and alertLevel != \"\"'>AND alert_level = #{alertLevel}</if>" +
            "<if test='startTime != null'>AND create_time >= #{startTime}</if>" +
            "<if test='endTime != null'>AND create_time &lt;= #{endTime}</if>" +
            "</where></script>")
    long count(@Param("taskId") String taskId,
               @Param("subAccount") String subAccount,
               @Param("status") String status,
               @Param("alertLevel") String alertLevel,
               @Param("startTime") LocalDateTime startTime,
               @Param("endTime") LocalDateTime endTime);

    /**
     * 本周对比上周统计
     */
    @Select("SELECT " +
            "COUNT(CASE WHEN DATE(create_time) >= CURDATE() - INTERVAL 7 DAY THEN 1 END) AS this_week_count, " +
            "COUNT(CASE WHEN DATE(create_time) >= CURDATE() - INTERVAL 14 DAY AND DATE(create_time) < CURDATE() - INTERVAL 7 DAY THEN 1 END) AS last_week_count " +
            "FROM tbl_queue_monitor_log")
    Map<String, Object> getWeekCompareStats();

    /**
     * 本月按告警级别统计
     */
    @Select("SELECT alert_level, COUNT(*) as count " +
            "FROM tbl_queue_monitor_log " +
            "WHERE MONTH(create_time) = MONTH(CURDATE()) AND YEAR(create_time) = YEAR(CURDATE()) " +
            "GROUP BY alert_level ORDER BY count DESC")
    List<Map<String, Object>> getCurrentMonthStatsByLevel();

    /**
     * 本月按状态统计
     */
    @Select("SELECT status, COUNT(*) as count " +
            "FROM tbl_queue_monitor_log " +
            "WHERE MONTH(create_time) = MONTH(CURDATE()) AND YEAR(create_time) = YEAR(CURDATE()) " +
            "GROUP BY status ORDER BY count DESC")
    List<Map<String, Object>> getCurrentMonthStatsByStatus();

    /**
     * 最近7天趋势
     */
    @Select("SELECT DATE(create_time) as date, COUNT(*) as count " +
            "FROM tbl_queue_monitor_log " +
            "WHERE create_time >= DATE_SUB(CURDATE(), INTERVAL 7 DAY) " +
            "GROUP BY DATE(create_time) ORDER BY date")
    List<Map<String, Object>> getLast7DaysTrend();

    /**
     * 平均处理时长
     */
    @Select("SELECT AVG(TIMESTAMPDIFF(MINUTE, create_time, processed_time)) as avg_handle_minutes " +
            "FROM tbl_queue_monitor_log " +
            "WHERE status = 'PROCESSED' AND processed_time IS NOT NULL " +
            "AND MONTH(create_time) = MONTH(CURDATE()) AND YEAR(create_time) = YEAR(CURDATE())")
    Map<String, Object> getAvgHandleTime();
}