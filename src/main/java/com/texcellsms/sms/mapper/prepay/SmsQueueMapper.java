package com.texcellsms.sms.mapper.prepay;

import com.texcellsms.sms.model.entity.TblAPITask;
import com.texcellsms.sms.model.vo.ScheduleTaskVO;
import com.texcellsms.sms.model.vo.TaskDetailVO;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
@Mapper
public interface SmsQueueMapper {

    // ==================== 任务列表（分页） ====================
    @Select({
            "<script>",
            "SELECT TaskName, TaskTime, ScheduleSendTime, TaskTimeZone, ScheduleStatus, TotalCount, CalleeCount, ",
            "       SubmitSuccCount, DRSuccCount, DRFailCount, Short_Message, SplitCount ",
            "FROM tbl_APIScheduleSend ",
            "<where>",
            "  SubAccount = #{subAccount} ", // 权限限制：当前用户的subAccount
            "  AND TaskTime >= #{threeDaysAgoTime} ", // 时间限制：最近3天
            "  <if test='taskName != null and taskName != \"\"'>",
            "    AND TaskName LIKE CONCAT('%', #{taskName}, '%')",
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
    List<ScheduleTaskVO> selectScheduleTaskList(
            @Param("subAccount") String subAccount, // 当前登录用户subAccount
            @Param("threeDaysAgoTime") String threeDaysAgoTime, // 3天前时间
            @Param("taskName") String taskName,
            @Param("scheduleStatus") Byte scheduleStatus,
            @Param("offset") Integer offset,
            @Param("pageSize") Integer pageSize
    );

    @Select({
            "<script>",
            "SELECT COUNT(*) FROM tbl_APIScheduleSend ",
            "<where>",
            "  SubAccount = #{subAccount} ",
            "  AND TaskTime >= #{threeDaysAgoTime} ",
            "  <if test='taskName != null and taskName != \"\"'>",
            "    AND TaskName LIKE CONCAT('%', #{taskName}, '%')",
            "  </if>",
            "  <if test='scheduleStatus != null'>",
            "    AND ScheduleStatus = #{scheduleStatus}",
            "  </if>",
            "</where>",
            "</script>"
    })
    Long countScheduleTaskList(
            @Param("subAccount") String subAccount,
            @Param("threeDaysAgoTime") String threeDaysAgoTime,
            @Param("taskName") String taskName,
            @Param("scheduleStatus") Byte scheduleStatus
    );

    // ==================== 任务详情（分页） ====================
    @Select({
            "<script>",
            "SELECT t1.SubmitMsgId, t1.Caller, t1.Callee, t1.RecvDRTime, t1.RecvDRStatus, t1.RecvDRError, t1.PKNumber, t1.PKTotal ",
            "FROM tbl_APITask t1 ",
            "INNER JOIN tbl_APIScheduleSend t2 ON t1.APITaskId = t2.TaskID ",
            "WHERE t2.SubAccount = #{subAccount} ", // 关联主表的subAccount
            "  AND t2.TaskTime >= #{threeDaysAgoTime} ", // 主表的时间限制
            "  AND t1.APITaskId = #{taskId} ",
            "  <if test='callee != null and callee != \"\"'>",
            "    AND t1.Callee LIKE CONCAT('%', #{callee}, '%')",
            "  </if>",
            "ORDER BY t1.RecvDRTime DESC ",
            "LIMIT #{offset}, #{pageSize}",
            "</script>"
    })
    @ResultType(TaskDetailVO.class)
    List<TaskDetailVO> selectTaskDetailList(
            @Param("subAccount") String subAccount,
            @Param("threeDaysAgoTime") String threeDaysAgoTime,
            @Param("taskId") String taskId,
            @Param("callee") String callee,
            @Param("offset") Integer offset,
            @Param("pageSize") Integer pageSize
    );

    @Select({
            "<script>",
            "SELECT COUNT(*) FROM tbl_APITask t1 ",
            "INNER JOIN tbl_APIScheduleSend t2 ON t1.APITaskId = t2.TaskID ",
            "WHERE t2.SubAccount = #{subAccount} ",
            "  AND t2.TaskTime >= #{threeDaysAgoTime} ",
            "  AND t1.APITaskId = #{taskId} ",
            "  <if test='callee != null and callee != \"\"'>",
            "    AND t1.Callee LIKE CONCAT('%', #{callee}, '%')",
            "  </if>",
            "</script>"
    })
    Long countTaskDetailList(
            @Param("subAccount") String subAccount,
            @Param("threeDaysAgoTime") String threeDaysAgoTime,
            @Param("taskId") String taskId,
            @Param("callee") String callee
    );

    // ==================== 导出全部详情 ====================
    @Select({
            "SELECT t1.SubmitMsgId, t1.Caller, t1.Callee, t1.RecvDRTime, t1.RecvDRStatus, t1.RecvDRError, t1.PKNumber, t1.PKTotal ",
            "FROM tbl_APITask t1 ",
            "INNER JOIN tbl_APIScheduleSend t2 ON t1.APITaskId = t2.TaskID ",
            "WHERE t2.SubAccount = #{subAccount} ",
            "  AND t2.TaskTime >= #{threeDaysAgoTime} ",
            "  AND t1.APITaskId = #{taskId} ",
            "ORDER BY t1.RecvDRTime DESC"
    })
    @ResultType(TblAPITask.class)
    List<TblAPITask> selectAllTaskDetailsByTaskId(
            @Param("subAccount") String subAccount,
            @Param("threeDaysAgoTime") String threeDaysAgoTime,
            @Param("taskId") String taskId
    );

    // ========== 新增：批量修改状态（伪删除） ==========
    @Update({
            "<script>",
            "UPDATE tbl_APIScheduleSend ",
            "SET ScheduleStatus = 4 ", // 改为删除状态4
            "WHERE SubAccount = #{subAccount} ", // 仅当前用户
            "  AND TaskTime >= #{threeDaysAgoTime} ", // 仅最近3天
            "  AND TaskID IN ",
            "<foreach collection='taskIds' item='taskId' open='(' separator=',' close=')'>",
            "  #{taskId}",
            "</foreach>",
            "</script>"
    })
    int batchUpdateScheduleStatus(
            @Param("subAccount") String subAccount,
            @Param("threeDaysAgoTime") String threeDaysAgoTime,
            @Param("taskIds") Set<String> taskIds // 用Set避免重复ID
    );
}