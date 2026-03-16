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

    // ==================== 任务列表（分页） ====================
    @Select({
            "<script>",
            "SELECT TaskName,taskId,TaskTime, ScheduleSendTime, TaskTimeZone, ScheduleStatus, TotalCount, CalleeCount, ",
            "       SubmitSuccCount, DRSuccCount, DRFailCount, Short_Message, SplitCount ",
            "FROM tbl_APIScheduleSend ",
            "<where>",
            "  SubAccount = #{subAccount} ", // 权限限制：当前登录用户的subAccount
            "  AND TaskTime >= #{threeDaysAgoTime} ", // 时间限制：最近3天
            "</where>",
            "ORDER BY TaskTime DESC ",
            "LIMIT #{offset}, #{pageSize}",
            "</script>"
    })
    @ResultType(ScheduleTaskVO.class)
    List<ScheduleTaskVO> selectScheduleTaskList(
            @Param("subAccount") String subAccount, // 当前登录用户subAccount
            @Param("threeDaysAgoTime") String threeDaysAgoTime, // 3天前时间
            @Param("offset") Integer offset,
            @Param("pageSize") Integer pageSize
    );

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

    // ==================== 任务详情（分页） ====================
    @Select({
            "<script>",
            "SELECT SubmitMsgId, Caller, Callee, RecvDRTime, RecvDRStatus, RecvDRError, PKNumber, PKTotal ",
            "FROM tbl_APITask",
            "WHERE APITaskId = #{taskId} ",
            "  <if test='callee != null and callee != \"\"'>",
            "    AND Callee LIKE CONCAT('%', #{callee}, '%')", // 移除错误的t1.前缀
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

    @Select({
            "<script>",
            "SELECT COUNT(*) FROM tbl_APITask",
            "WHERE APITaskId = #{taskId} ",
            "  <if test='callee != null and callee != \"\"'>",
            "    AND Callee LIKE CONCAT('%', #{callee}, '%')", // 移除错误的t1.前缀
            "  </if>",
            "</script>"
    })
    Long countTaskDetailList(
            @Param("taskId") String taskId,
            @Param("callee") String callee
    );

    // ==================== 导出全部详情 ====================
    @Select({
            "SELECT SubmitMsgId, Caller, Callee, RecvDRTime, RecvDRStatus, RecvDRError, PKNumber, PKTotal ",
            "FROM tbl_APITask",
            "WHERE APITaskId = #{taskId} ",
            "ORDER BY RecvDRTime DESC"
    })
    @ResultType(TblAPITask.class)
    List<TblAPITask> selectAllTaskDetailsByTaskId(
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

    // ==================== 【新增】DR状态统计 ====================
    @Select({
            "<script>",
            "SELECT APITaskId, RecvDRStatus, COUNT(*) AS count ",
            "FROM tbl_APITask ",
            "WHERE APITaskId IN ",
            "<foreach collection='taskIds' item='taskId' open='(' separator=',' close=')'>",
            "    #{taskId}",
            "</foreach>",
            "GROUP BY APITaskId, RecvDRStatus",
            "</script>"
    })
    @MapKey("APITaskId")
    List<Map<String, Object>> selectDrStatsByTaskIds(@Param("taskIds") List<String> taskIds);

}
