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
     * 按任务ID分组统计DR状态
     */
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