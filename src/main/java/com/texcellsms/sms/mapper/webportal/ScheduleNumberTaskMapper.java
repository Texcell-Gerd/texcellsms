package com.texcellsms.sms.mapper.webportal;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;
import java.util.Date;

@Repository
@Mapper
public interface ScheduleNumberTaskMapper {

    // 插入预上传号码任务
    @Insert({
            "INSERT INTO tbl_ScheduleSend_NumberTask (",
            "    SubAccount, ",
            "    SubmitTime, ",
            "    PreTaskId, ",
            "    ValidTill ",
            ") VALUES (",
            "    #{subAccount}, ",
            "    #{submitTime}, ",
            "    #{preTaskId}, ",
            "    #{validTill} ",
            ")"
    })
    int insertScheduleNumberTask(
            @Param("subAccount") String subAccount,
            @Param("submitTime") String submitTime,
            @Param("preTaskId") String preTaskId,
            @Param("validTill") String validTill
    );

    // 校验预任务是否有效（和C#逻辑一致）
    @Select({
            "SELECT COUNT(*) ",
            "FROM tbl_ScheduleSend_NumberTask ",
            "WHERE SubAccount = #{userName} ",
            "  AND PreTaskId = #{preTaskId} ",
            "  AND (ValidTill - UNIX_TIMESTAMP(NOW()) >= 10)"
    })
    int checkPreTaskValid(
            @Param("userName") String userName,
            @Param("preTaskId") String preTaskId
    );

}