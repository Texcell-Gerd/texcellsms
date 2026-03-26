package com.texcellsms.sms.mapper.webportal;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;
import java.util.Date;

@Repository
@Mapper
public interface SmsScheduleSendMapper {

    // 插入发送任务到 tbl_APIScheduleSend
    @Insert({
            "INSERT INTO tbl_APIScheduleSend (",
            "    TaskID, ",
            "    ScheduleSendTime, ",
            "    CalleeCount, ",
            "    SplitCount, ",
            "    TotalCount, ",
            "    TaskTime, ",
            "    TaskIP, ",
            "    SubAccount, ",
            "    Short_Message, ",
            "    TaskName, ",
            "    SubmitSuccCount, ",
            "    SubmitFailCount, ",
            "    DRSuccCount, ",
            "    DRFailCount ",
            ") VALUES (",
            "    #{taskId}, ",
            "    #{scheduleSendTime}, ",
            "    #{calleeCount}, ",
            "    #{splitCount}, ",
            "    #{totalCount}, ",
            "    #{taskTime}, ",
            "    #{taskIp}, ",
            "    #{subAccount}, ",
            "    #{shortMessage}, ",
            "    #{taskName}, ",
            "    #{submitSuccCount}, ",
            "    #{submitFailCount}, ",
            "    #{drSuccCount}, ",
            "    #{drFailCount} ",
            ")"
    })
    int insertAPIScheduleSend(
            @Param("taskId") String taskId,
            @Param("scheduleSendTime") Date scheduleSendTime,
            @Param("calleeCount") Integer calleeCount,
            @Param("splitCount") Integer splitCount,
            @Param("totalCount") Integer totalCount,
            @Param("taskTime") Date taskTime,
            @Param("taskIp") String taskIp,
            @Param("subAccount") String subAccount,
            @Param("shortMessage") String shortMessage,
            @Param("taskName") String taskName,
            @Param("submitSuccCount") Integer submitSuccCount,
            @Param("submitFailCount") Integer submitFailCount,
            @Param("drSuccCount") Integer drSuccCount,
            @Param("drFailCount") Integer drFailCount
    );

}