package com.texcellsms.sms.mapper.webportal;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

@Repository
public interface ScheduleNumberTaskMapper {

    @Insert({
            "INSERT INTO tbl_ScheduleSend_NumberTask (",
            "SubAccount, SubmitTime, PreTaskId, ValidTill)",
            "VALUES (#{subAccount}, #{submitTime}, #{preTaskId}, #{validTill})"
    })
    int insertScheduleNumberTask(
            @Param("subAccount") String subAccount,
            @Param("submitTime") String submitTime,
            @Param("preTaskId") String preTaskId,
            @Param("validTill") String validTill
    );

    @Select({
            "SELECT COUNT(*) FROM tbl_ScheduleSend_NumberTask ",
            "WHERE SubAccount = #{userName} AND PreTaskId = #{preTaskId} ",
            "AND (ValidTill - UNIX_TIMESTAMP(NOW()) >= 10)"
    })
    int checkPreTaskValid(
            @Param("userName") String userName,
            @Param("preTaskId") String preTaskId
    );

    @Delete("DELETE FROM tbl_ScheduleSend_NumberTask WHERE SubAccount=#{userName} AND PreTaskId=#{preTaskId}")
    int deletePreTask(
            @Param("userName") String userName,
            @Param("preTaskId") String preTaskId
    );
}