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
     * 分页查询所有客户的任务列表（内部管理用）
     */
    @Select({
            "<script>",
            "SELECT TaskName, TaskID, SubAccount, ",
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
            "  <if test='scheduleStatus != null'>",
            "    AND ScheduleStatus = #{scheduleStatus}",
            "  </if>",
            "</where>",
            "</script>"
    })
    Long countAllScheduleTaskList(
            @Param("subAccount") String subAccount,
            @Param("taskId") String taskId,
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
}