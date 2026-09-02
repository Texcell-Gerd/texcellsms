package com.texcellsms.sms.mapper.webportal;

import com.texcellsms.sms.model.entity.TblAPITask;
import com.texcellsms.sms.model.vo.ScheduleTaskVO;
import com.texcellsms.sms.model.vo.TaskDetailVO;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
@Mapper
public interface SmsQueueMapper {

    /**
     * 分页查询任务列表
     */
    @Select({
            "<script>",
            "SELECT TaskName,taskId,",
            "       DATE_FORMAT(TaskTime, '%Y-%m-%d %H:%i:%s') AS TaskTime, ",
            "       DATE_FORMAT(ScheduleSendTime, '%Y-%m-%d %H:%i:%s') AS ScheduleSendTime, ",
            "       TaskTimeZone, ScheduleStatus, TotalCount, CalleeCount, ",
            "       SubmitSuccCount, DRSuccCount, DRFailCount, Short_Message, SplitCount ",
            "FROM tbl_APIScheduleSend ",
            "<where>",
            "  SubAccount = #{subAccount} ",
            "  AND TaskTime >= #{threeDaysAgoTime} ",
            "</where>",
            "ORDER BY TaskTime DESC ",
            "LIMIT #{offset}, #{pageSize}",
            "</script>"
    })
    @ResultType(ScheduleTaskVO.class)
    List<ScheduleTaskVO> selectScheduleTaskList(
            @Param("subAccount") String subAccount,
            @Param("threeDaysAgoTime") String threeDaysAgoTime,
            @Param("offset") Integer offset,
            @Param("pageSize") Integer pageSize
    );

    /**
     * 统计任务总数
     */
    @Select({
            "<script>",
            "SELECT COUNT(*) FROM tbl_APIScheduleSend ",
            "<where>",
            "  SubAccount = #{subAccount} ",
            "  AND TaskTime >= #{threeDaysAgoTime} ",
            "</where>",
            "</script>"
    })
    Long countScheduleTaskList(
            @Param("subAccount") String subAccount,
            @Param("threeDaysAgoTime") String threeDaysAgoTime
    );

    /**
     * 分页查询任务详情
     */
    @Select({
            "<script>",
            "SELECT SubmitMsgId, Caller, Callee, ",
            "       DATE_FORMAT(RecvDRTime, '%Y-%m-%d %H:%i:%s') AS RecvDRTime, ",
            "       RecvDRStatus, RecvDRError, PKNumber, PKTotal ",
            "FROM tbl_APITask",
            "WHERE APITaskId = #{taskId} ",
            "  <if test='callee != null and callee != \"\"'>",
            "    AND Callee LIKE CONCAT('%', #{callee}, '%')",
            "  </if>",
            "ORDER BY RecvDRTime DESC ",
            "LIMIT #{offset}, #{pageSize}",
            "</script>"
    })
    @ResultType(TaskDetailVO.class)
    List<TaskDetailVO> selectTaskDetailList(
            @Param("taskId") String taskId,
            @Param("callee") String callee,
            @Param("offset") Integer offset,
            @Param("pageSize") Integer pageSize
    );

    /**
     * 统计任务详情数量
     */
    @Select({
            "<script>",
            "SELECT COUNT(*) FROM tbl_APITask",
            "WHERE APITaskId = #{taskId} ",
            "  <if test='callee != null and callee != \"\"'>",
            "    AND Callee LIKE CONCAT('%', #{callee}, '%')",
            "  </if>",
            "</script>"
    })
    Long countTaskDetailList(
            @Param("taskId") String taskId,
            @Param("callee") String callee
    );

    /**
     * 查询全部任务详情（导出用）
     */
    @Select({
            "SELECT SubmitMsgId, Caller, Callee, ",
            "       DATE_FORMAT(RecvDRTime, '%Y-%m-%d %H:%i:%s') AS RecvDRTime, ",
            "       RecvDRStatus, RecvDRError, PKNumber, PKTotal ",
            "FROM tbl_APITask",
            "WHERE APITaskId = #{taskId} ",
            "ORDER BY RecvDRTime DESC"
    })
    @ResultType(TblAPITask.class)
    List<TblAPITask> selectAllTaskDetailsByTaskId(
            @Param("taskId") String taskId
    );

    /**
     * 批量更新任务状态（伪删除）
     */
    @Update({
            "<script>",
            "UPDATE tbl_APIScheduleSend ",
            "SET ScheduleStatus = 4 ",
            "WHERE SubAccount = #{subAccount} ",
            "  AND TaskTime >= #{threeDaysAgoTime} ",
            "  AND TaskID IN ",
            "<foreach collection='taskIds' item='taskId' open='(' separator=',' close=')'>",
            "  #{taskId}",
            "</foreach>",
            "</script>"
    })
    int batchUpdateScheduleStatus(
            @Param("subAccount") String subAccount,
            @Param("threeDaysAgoTime") String threeDaysAgoTime,
            @Param("taskIds") Set<String> taskIds
    );

    /**
     * 批量更新任务状态（伪删除）
     * 内部人员使用，可操作所有客户的预约任务，仅限最近3天数据
     * 只能删除状态为 0（待发送）的任务
     */
    @Update({
            "<script>",
            "UPDATE tbl_APIScheduleSend ",
            "SET ScheduleStatus = 4 ",
            "WHERE TaskTime >= #{threeDaysAgoTime} ",
            "  AND ScheduleStatus = 0 ",
            "  AND TaskID IN ",
            "<foreach collection='taskIds' item='taskId' open='(' separator=',' close=')'>",
            "  #{taskId}",
            "</foreach>",
            "</script>"
    })
    int batchUpdateScheduleStatusForAdmin(
            @Param("taskIds") Set<String> taskIds,
            @Param("threeDaysAgoTime") String threeDaysAgoTime
    );


    /**
     * 按任务ID分组统计DR状态（成功/失败）
     */
    @Select({
            "<script>",
            "SELECT APITaskId, ",
            "SUM(CASE WHEN RecvDRStatus = 'DELIVRD' THEN 1 ELSE 0 END) AS drSuccCount, ",
            "SUM(CASE WHEN RecvDRStatus != 'DELIVRD' AND RecvDRError IS NOT NULL AND RecvDRError != '' THEN 1 ELSE 0 END) AS drFailCount ",
            "FROM tbl_APITask ",
            "WHERE APITaskId IN ",
            "<foreach collection='taskIds' item='taskId' open='(' separator=',' close=')'>",
            "#{taskId}",
            "</foreach>",
            "GROUP BY APITaskId",
            "</script>"
    })
    @MapKey("APITaskId")
    List<Map<String, Object>> selectDrStatsByTaskIds(@Param("taskIds") List<String> taskIds);


    /**
     * 统计单个任务的DR状态（成功/失败）
     * 成功：RecvDRStatus = 'DELIVRD'
     * 失败：RecvDRStatus 不为空，且不等于 'DELIVRD'
     * 未回执：RecvDRStatus 为空，不计入统计
     */
    @Select("SELECT " +
            "SUM(CASE WHEN RecvDRStatus = 'DELIVRD' THEN 1 ELSE 0 END) AS drSuccCount, " +
            "SUM(CASE WHEN RecvDRStatus IS NOT NULL AND RecvDRStatus != '' AND RecvDRStatus != 'DELIVRD' THEN 1 ELSE 0 END) AS drFailCount " +
            "FROM tbl_APITask " +
            "WHERE APITaskId = #{taskId}")
    Map<String, Object> selectDrStatsByTaskId(@Param("taskId") String taskId);

    /**
     * 根据taskId查询任务归属账号，用于鉴权
     */
    @Select("SELECT SubAccount FROM tbl_APIScheduleSend WHERE TaskID = #{taskId}")
    String getTaskOwnerByTaskId(@Param("taskId") String taskId);

    /**
     * 获取任务汇总统计，对外API展示
     */
    @Select("SELECT TotalCount,CalleeCount,SubmitSuccCount,DRSuccCount,DRFailCount,ScheduleStatus " +
            "FROM tbl_APIScheduleSend WHERE TaskID = #{taskId}")
    Map<String,Object> getTaskSummary(@Param("taskId") String taskId);


    /**
     * 分页查询所有客户的任务列表（内部管理用）
     */
    @Select({
            "<script>",
            "SELECT TaskName, TaskID, SubAccount, TaskIP, ",
            "       DATE_FORMAT(TaskTime, '%Y-%m-%d %H:%i:%s') AS TaskTime, ",
            "       DATE_FORMAT(ScheduleSendTime, '%Y-%m-%d %H:%i:%s') AS ScheduleSendTime, ",
            "       TaskTimeZone, ScheduleStatus, TotalCount, CalleeCount, ",
            "       SubmitSuccCount, DRSuccCount, DRFailCount, Short_Message, SplitCount ",
            "FROM tbl_APIScheduleSend ",
            "<where>",
            "  TaskTime >= #{threeDaysAgoTime} ",
            "  <if test='subAccount != null and subAccount != \"\"'>",
            "    AND SubAccount LIKE CONCAT('%', #{subAccount}, '%')",
            "  </if>",
            "  <if test='taskId != null and taskId != \"\"'>",
            "    AND TaskID LIKE CONCAT('%', #{taskId}, '%')",
            "  </if>",
            "  <if test='taskIp != null and taskIp != \"\"'>",
            "    AND TaskIP LIKE CONCAT('%', #{taskIp}, '%')",
            "  </if>",
            "  <if test='scheduleStatus != null'>",
            "    AND ScheduleStatus = #{scheduleStatus}",
            "  </if>",
            "</where>",
            "ORDER BY TaskTime DESC ",
            "LIMIT #{offset}, #{pageSize}",
            "</script>"
    })
    @ResultType(ScheduleTaskVO.class)
    List<ScheduleTaskVO> selectAllScheduleTaskList(
            @Param("subAccount") String subAccount,
            @Param("taskId") String taskId,
            @Param("taskIp") String taskIp,
            @Param("scheduleStatus") Integer scheduleStatus,
            @Param("threeDaysAgoTime") String threeDaysAgoTime,
            @Param("offset") Integer offset,
            @Param("pageSize") Integer pageSize
    );

    /**
     * 统计所有客户的任务总数（内部管理用）
     */
    @Select({
            "<script>",
            "SELECT COUNT(*) FROM tbl_APIScheduleSend ",
            "<where>",
            "  TaskTime >= #{threeDaysAgoTime} ",
            "  <if test='subAccount != null and subAccount != \"\"'>",
            "    AND SubAccount LIKE CONCAT('%', #{subAccount}, '%')",
            "  </if>",
            "  <if test='taskId != null and taskId != \"\"'>",
            "    AND TaskID LIKE CONCAT('%', #{taskId}, '%')",
            "  </if>",
            "  <if test='taskIp != null and taskIp != \"\"'>",
            "    AND TaskIP LIKE CONCAT('%', #{taskIp}, '%')",
            "  </if>",
            "  <if test='scheduleStatus != null'>",
            "    AND ScheduleStatus = #{scheduleStatus}",
            "  </if>",
            "</where>",
            "</script>"
    })
    Long countAllScheduleTaskList(
            @Param("subAccount") String subAccount,
            @Param("taskId") String taskId,
            @Param("taskIp") String taskIp,
            @Param("scheduleStatus") Integer scheduleStatus,
            @Param("threeDaysAgoTime") String threeDaysAgoTime
    );

    /**
     * 获取近半小时客户提交量排行
     */
    @Select("SELECT SubAccount as subAccount, SUM(TotalCount) as totalCount FROM tbl_APIScheduleSend " +
            "WHERE TaskTime >= DATE_SUB(NOW(), INTERVAL 30 MINUTE) AND SubAccount IS NOT NULL " +
            "GROUP BY SubAccount ORDER BY totalCount DESC LIMIT 10")
    List<Map<String, Object>> getCustomerRankingLastHalfHour();

    /**
     * 查询一直未更新DR的任务
     */
    @Select({
            "<script>",
            "SELECT ",
            "  s.TaskID as taskId, ",
            "  s.SubAccount as subAccount, ",
            "  s.TaskName as taskName, ",
            "  s.TotalCount as totalCount, ",
            "  s.SubmitSuccCount as submitSuccCount, ",
            "  s.DRSuccCount as drSuccCount, ",
            "  s.DRFailCount as drFailCount, ",
            "  s.TaskTime as taskTimeStr, ",
            "  (s.TotalCount - s.SubmitSuccCount) as pendingCount, ",
            "  TIMESTAMPDIFF(MINUTE, STR_TO_DATE(s.TaskTime, '%Y-%m-%d %H:%i:%s.%f'), NOW()) as elapsedMinutes, ",
            "  (",
            "    SELECT COUNT(*) FROM tbl_APITask t ",
            "    WHERE t.APITaskId = s.TaskID ",
            "    AND (t.RecvDRStatus IS NULL OR t.RecvDRStatus = '' OR ",
            "         t.RecvDRTime IS NULL OR t.RecvDRTime = '')",
            "  ) as detailPendingCount ",
            "FROM tbl_APIScheduleSend s ",
            "WHERE STR_TO_DATE(s.TaskTime, '%Y-%m-%d %H:%i:%s.%f') >= CURDATE() ",
            "  AND s.TotalCount = s.SubmitSuccCount ",
            "  AND TIMESTAMPDIFF(MINUTE, STR_TO_DATE(s.TaskTime, '%Y-%m-%d %H:%i:%s.%f'), NOW()) >= #{minutes} ",
            "  AND (",
            "    SELECT COUNT(*) FROM tbl_APITask t ",
            "    WHERE t.APITaskId = s.TaskID ",
            "    AND (t.RecvDRStatus IS NULL OR t.RecvDRStatus = '' OR ",
            "         t.RecvDRTime IS NULL OR t.RecvDRTime = '')",
            "  ) > 0 ",
            "ORDER BY STR_TO_DATE(s.TaskTime, '%Y-%m-%d %H:%i:%s.%f') ASC",
            "</script>"
    })
    List<Map<String, Object>> findTimeoutTasks(@Param("minutes") int minutes);

    /**
     * 执行修复SQL - 更新明细表中未更新的记录
     */
    @Update("UPDATE tbl_APITask SET " +
            "RecvDRTime = DATE_FORMAT(CURRENT_TIMESTAMP(), '%Y-%m-%d %T'), " +
            "RecvDRStatus = 'DELIVRD', " +
            "RecvDRError = '000', " +
            "DLRSendStatus = 1 " +
            "WHERE APITaskId = #{taskId} " +
            "AND (RecvDRStatus = '' OR RecvDRStatus IS NULL) " +
            "AND (RecvDRError = '' OR RecvDRError IS NULL) " +
            "AND (RecvDRTime = '' OR RecvDRTime IS NULL)")
    int executeFixSql(@Param("taskId") String taskId);

    /**
     * 查询任务中未更新DR的号码列表（分页）
     */
    @Select("SELECT t.Callee as mobileNumber " +
            "FROM tbl_APITask t " +
            "WHERE t.APITaskId = #{taskId} " +
            "AND (t.RecvDRStatus IS NULL OR t.RecvDRStatus = '' OR " +
            "     t.RecvDRTime IS NULL OR t.RecvDRTime = '') " +
            "ORDER BY t.UNID ASC " +
            "LIMIT #{offset}, #{limit}")
    List<Map<String, Object>> findPendingDrNumbers(@Param("taskId") String taskId,
                                                   @Param("offset") int offset,
                                                   @Param("limit") int limit);

    /**
     * 统计任务中未更新DR的号码总数
     */
    @Select("SELECT COUNT(*) " +
            "FROM tbl_APITask t " +
            "WHERE t.APITaskId = #{taskId} " +
            "AND (t.RecvDRStatus IS NULL OR t.RecvDRStatus = '' OR " +
            "     t.RecvDRTime IS NULL OR t.RecvDRTime = '')")
    long countPendingDrNumbers(@Param("taskId") String taskId);

    /**
     * 批量修复任务DR（批量更新明细表）
     */
    @Update({
            "<script>",
            "UPDATE tbl_APITask SET ",
            "    RecvDRStatus = 'DELIVRD', ",
            "    RecvDRError = '000', ",
            "    RecvDRTime = DATE_FORMAT(NOW(), '%Y-%m-%d %H:%i:%s'), ",
            "    DLRSendStatus = 1 ",
            "WHERE APITaskId IN ",
            "<foreach collection='taskIds' item='id' open='(' separator=',' close=')'>",
            "    #{id}",
            "</foreach>",
            "  AND (RecvDRStatus IS NULL OR RecvDRStatus = '' OR RecvDRStatus = 'UNKNOWN')",
            "  AND (RecvDRError IS NULL OR RecvDRError = '' OR RecvDRError = 'UNKNOWN')",
            "  AND (RecvDRTime IS NULL OR RecvDRTime = '' OR RecvDRTime = 'UNKNOWN')",
            "</script>"
    })
    int batchFixTaskDr(@Param("taskIds") List<String> taskIds);

    /**
     * 批量更新任务主表统计（修复后更新DR计数）
     * 使用 CASE WHEN 一次性更新所有任务
     */
    @Update({
            "<script>",
            "UPDATE tbl_APIScheduleSend SET ",
            "    DRSuccCount = CASE TaskID ",
            "    <foreach collection='taskIds' item='id'>",
            "        WHEN #{id} THEN (SELECT COUNT(*) FROM tbl_APITask WHERE APITaskId = #{id} AND RecvDRStatus = 'DELIVRD')",
            "    </foreach>",
            "    END, ",
            "    DRFailCount = CASE TaskID ",
            "    <foreach collection='taskIds' item='id'>",
            "        WHEN #{id} THEN (SELECT COUNT(*) FROM tbl_APITask WHERE APITaskId = #{id} AND RecvDRStatus != 'DELIVRD' AND RecvDRStatus IS NOT NULL AND RecvDRStatus != '')",
            "    </foreach>",
            "    END ",
            "WHERE TaskID IN ",
            "<foreach collection='taskIds' item='id' open='(' separator=',' close=')'>",
            "    #{id}",
            "</foreach>",
            "</script>"
    })
    int batchUpdateTaskStats(@Param("taskIds") List<String> taskIds);

    /**
     * 查询任务的10个号码样本（用于FEE表校验）
     * 返回号码、表后缀、任务提交时间、SubmitMsgId
     */
    @Select({
            "SELECT ",
            "    t.Callee, ",
            "    t.SubAccount, ",
            "    DATE_FORMAT(t.SubmitMsgTime, '%Y%m%d') as tableSuffix, ",
            "    DATE_FORMAT(t.SubmitMsgTime, '%Y-%m-%d %H:%i:%s') as taskTime ",
            "FROM tbl_APITask t ",
            "WHERE t.APITaskId = #{taskId} ",
            "  AND t.Callee IS NOT NULL ",
            "  AND t.Callee != '' ",
            "  AND t.SubmitMsgTime IS NOT NULL ",
            "LIMIT 10"
    })
    List<Map<String, Object>> getTaskSampleCallees(@Param("taskId") String taskId);

    /**
     * 查询指定客户的超时任务
     * 只查询当天的任务，且只查询 status = PENDING 的（避免重复扫描已处理的）
     */
    @Select({
            "SELECT ",
            "  s.TaskID as taskId, ",
            "  s.SubAccount as subAccount, ",
            "  s.TaskName as taskName, ",
            "  s.TotalCount as totalCount, ",
            "  s.SubmitSuccCount as submitSuccCount, ",
            "  s.DRSuccCount as drSuccCount, ",
            "  s.DRFailCount as drFailCount, ",
            "  s.TaskTime as taskTimeStr, ",
            "  TIMESTAMPDIFF(MINUTE, STR_TO_DATE(s.TaskTime, '%Y-%m-%d %H:%i:%s.%f'), NOW()) as elapsedMinutes, ",
            "  (",
            "    SELECT COUNT(*) FROM tbl_APITask t ",
            "    WHERE t.APITaskId = s.TaskID ",
            "    AND (t.RecvDRStatus IS NULL OR t.RecvDRStatus = '' OR ",
            "         t.RecvDRTime IS NULL OR t.RecvDRTime = '')",
            "  ) as detailPendingCount ",
            "FROM tbl_APIScheduleSend s ",
            "WHERE s.SubAccount = #{subAccount} ",
            "  AND s.TotalCount = s.SubmitSuccCount ",
            "  AND DATE(STR_TO_DATE(s.TaskTime, '%Y-%m-%d %H:%i:%s.%f')) = CURDATE() ",  // ✅ 只查当天
            "  AND TIMESTAMPDIFF(MINUTE, STR_TO_DATE(s.TaskTime, '%Y-%m-%d %H:%i:%s.%f'), NOW()) >= #{timeoutMinutes} ",
            "  AND (",
            "    SELECT COUNT(*) FROM tbl_APITask t ",
            "    WHERE t.APITaskId = s.TaskID ",
            "    AND (t.RecvDRStatus IS NULL OR t.RecvDRStatus = '' OR ",
            "         t.RecvDRTime IS NULL OR t.RecvDRTime = '')",
            "  ) > 0 ",
            "  AND NOT EXISTS (",  // ✅ 排除已处理的（避免重复扫描）
            "    SELECT 1 FROM tbl_queue_monitor_log l ",
            "    WHERE l.task_id = s.TaskID ",
            "    AND l.status = 'PROCESSED'",
            "  ) ",
            "ORDER BY STR_TO_DATE(s.TaskTime, '%Y-%m-%d %H:%i:%s.%f') ASC ",
            "LIMIT 10"  // ✅ 每次最多处理10个任务，避免一次处理太多
    })
    List<Map<String, Object>> findTimeoutTasksForCustomer(
            @Param("subAccount") String subAccount,
            @Param("timeoutMinutes") int timeoutMinutes
    );
}