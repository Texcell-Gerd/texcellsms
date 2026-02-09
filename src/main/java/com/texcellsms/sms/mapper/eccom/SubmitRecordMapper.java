package com.texcellsms.sms.mapper.eccom;

import com.texcellsms.sms.model.DTO.SubmitRecordDTO;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
@Mapper
public interface SubmitRecordMapper {

    // 1. 分页查询方法 - 保留索引提示
    @Select({
            "SELECT /*+ INDEX(sb idx_submitmsgId) INDEX(fb idx_recvmsgId) */ ",
            "    sb.SourAccount,",
            "    sb.SubmitMsgId,",
            "    sb.SubmitMsgTime,",
            "    sb.Caller,",
            "    sb.Callee,",
            "    sb.SM_Length,",
            "    sb.ChargeFee,",
            "    ROUND(IFNULL(CAST(sb.ChargeFee AS DECIMAL)/10000, 0), 4) as actualChargeFee,",
            "    CASE ",
            "        WHEN sb.DCS='0' THEN CAST(UNHEX(sb.Short_Message) AS CHAR) ",
            "        WHEN sb.DCS='1' THEN CAST(CONVERT(UNHEX(sb.Short_Message) USING ASCII) AS CHAR)  ",
            "        WHEN sb.DCS='8' THEN CAST(CONVERT(UNHEX(sb.Short_Message) USING UCS2) AS CHAR) ",
            "        ELSE sb.Short_Message ",
            "    END as shortMessage,",
            "    sb.MCC as mcc,",
            "    sb.MNC as mnc,",
            "    fb.InnerReportState as fbInnerReportState,",
            "    fb.RecvDRTime as recvDRTime,",
            "    fb.InnerReportError as innerReportError",
            "FROM Submit_Bill_${tableSuffix} sb",
            "LEFT JOIN Fee_Bill_${tableSuffix} fb ON sb.SubmitMsgId = fb.RecvMsgId",
            "WHERE 1=1",
            "  AND (sb.SourAccount = #{sourAccount} OR #{sourAccount} IS NULL)",
            "  AND (sb.SubmitMsgTime >= #{startTimeStr} OR #{startTimeStr} IS NULL)",
            "  AND (sb.SubmitMsgTime <= #{endTimeStr} OR #{endTimeStr} IS NULL)",
            "  AND (sb.Callee = #{callee} OR #{callee} IS NULL)",
            "  AND (sb.MCC = #{mcc} OR #{mcc} IS NULL)",
            "  AND (sb.MNC = #{mnc} OR #{mnc} IS NULL)",
            "  AND (fb.InnerReportState = #{innerReportState} OR #{innerReportState} IS NULL)",
            "  AND (fb.InnerReportError = #{innerReportError} OR #{innerReportError} IS NULL)",
            "LIMIT #{pageSize} OFFSET #{offset}"
    })
    @Results({
            @Result(column = "SourAccount", property = "sourAccount"),
            @Result(column = "SubmitMsgId", property = "submitMsgId"),
            @Result(column = "SubmitMsgTime", property = "submitMsgTime"),
            @Result(column = "Caller", property = "caller"),
            @Result(column = "Callee", property = "callee"),
            @Result(column = "SM_Length", property = "smLength"),
            @Result(column = "ChargeFee", property = "chargeFee"),
            @Result(column = "actualChargeFee", property = "actualChargeFee"),
            @Result(column = "shortMessage", property = "shortMessage"),
            @Result(column = "mcc", property = "mcc"),
            @Result(column = "mnc", property = "mnc"),
            @Result(column = "fbInnerReportState", property = "fbInnerReportState"),
            @Result(column = "recvDRTime", property = "recvDRTime"),
            @Result(column = "innerReportError", property = "innerReportError")
    })
    List<SubmitRecordDTO> querySubmitRecordsWithJoin(
            @Param("tableSuffix") String tableSuffix,
            @Param("sourAccount") String sourAccount,
            @Param("startTimeStr") String startTimeStr,
            @Param("endTimeStr") String endTimeStr,
            @Param("callee") String callee,
            @Param("mcc") String mcc,
            @Param("mnc") String mnc,
            @Param("innerReportState") String innerReportState,
            @Param("innerReportError") String innerReportError,
            @Param("pageSize") Integer pageSize,
            @Param("offset") Integer offset
    );

    // 2. 导出查询方法 - 保留索引提示
    @Select({
            "SELECT /*+ INDEX(sb idx_submitmsgId) INDEX(fb idx_recvmsgId) */ ",
            "    sb.SourAccount,",
            "    sb.SubmitMsgId,",
            "    sb.SubmitMsgTime,",
            "    sb.Caller,",
            "    sb.Callee,",
            "    sb.SM_Length,",
            "    sb.ChargeFee,",
            "    ROUND(IFNULL(CAST(sb.ChargeFee AS DECIMAL)/10000, 0), 4) as actualChargeFee,",
            "    CASE ",
            "        WHEN sb.DCS='0' THEN CAST(UNHEX(sb.Short_Message) AS CHAR) ",
            "        WHEN sb.DCS='1' THEN CAST(CONVERT(UNHEX(sb.Short_Message) USING ASCII) AS CHAR)  ",
            "        WHEN sb.DCS='8' THEN CAST(CONVERT(UNHEX(sb.Short_Message) USING UCS2) AS CHAR) ",
            "        ELSE sb.Short_Message ",
            "    END as shortMessage,",
            "    sb.MCC as mcc,",
            "    sb.MNC as mnc,",
            "    fb.InnerReportState as fbInnerReportState,",
            "    fb.RecvDRTime as recvDRTime,",
            "    fb.InnerReportError as innerReportError",
            "FROM Submit_Bill_${tableSuffix} sb",
            "LEFT JOIN Fee_Bill_${tableSuffix} fb ON sb.SubmitMsgId = fb.RecvMsgId",
            "WHERE 1=1",
            "  AND (sb.SourAccount = #{sourAccount} OR #{sourAccount} IS NULL)",
            "  AND (sb.SubmitMsgTime >= #{startTimeStr} OR #{startTimeStr} IS NULL)",
            "  AND (sb.SubmitMsgTime <= #{endTimeStr} OR #{endTimeStr} IS NULL)",
            "  AND (sb.Callee = #{callee} OR #{callee} IS NULL)",
            "  AND (sb.MCC = #{mcc} OR #{mcc} IS NULL)",
            "  AND (sb.MNC = #{mnc} OR #{mnc} IS NULL)",
            "  AND (fb.InnerReportState = #{innerReportState} OR #{innerReportState} IS NULL)",
            "  AND (fb.InnerReportError = #{innerReportError} OR #{innerReportError} IS NULL)"
    })
    @Results({
            @Result(column = "SourAccount", property = "sourAccount"),
            @Result(column = "SubmitMsgId", property = "submitMsgId"),
            @Result(column = "SubmitMsgTime", property = "submitMsgTime"),
            @Result(column = "Caller", property = "caller"),
            @Result(column = "Callee", property = "callee"),
            @Result(column = "SM_Length", property = "smLength"),
            @Result(column = "ChargeFee", property = "chargeFee"),
            @Result(column = "actualChargeFee", property = "actualChargeFee"),
            @Result(column = "shortMessage", property = "shortMessage"),
            @Result(column = "mcc", property = "mcc"),
            @Result(column = "mnc", property = "mnc"),
            @Result(column = "fbInnerReportState", property = "fbInnerReportState"),
            @Result(column = "recvDRTime", property = "recvDRTime"),
            @Result(column = "innerReportError", property = "innerReportError")
    })
    List<SubmitRecordDTO> querySubmitRecordsForExportWithJoin(
            @Param("tableSuffix") String tableSuffix,
            @Param("sourAccount") String sourAccount,
            @Param("startTimeStr") String startTimeStr,
            @Param("endTimeStr") String endTimeStr,
            @Param("callee") String callee,
            @Param("mcc") String mcc,
            @Param("mnc") String mnc,
            @Param("innerReportState") String innerReportState,
            @Param("innerReportError") String innerReportError
    );

    // 3. 统计方法
    @Select("SELECT COUNT(*) FROM Submit_Bill_${tableSuffix} " +
            "WHERE (SourAccount = #{sourAccount} OR #{sourAccount} IS NULL) " +
            "AND (SubmitMsgTime >= #{startTimeStr} OR #{startTimeStr} IS NULL) " +
            "AND (SubmitMsgTime <= #{endTimeStr} OR #{endTimeStr} IS NULL)")
    Long countSubmitTotal(
            @Param("tableSuffix") String tableSuffix,
            @Param("sourAccount") String sourAccount,
            @Param("startTimeStr") String startTimeStr,
            @Param("endTimeStr") String endTimeStr
    );

    @Select("SELECT InnerReportState, COUNT(*) AS count " +
            "FROM Fee_Bill_${tableSuffix} " +
            "WHERE (SourAccount = #{sourAccount} OR #{sourAccount} IS NULL) " +
            "AND (TransMsgTime >= #{startTimeStr} OR #{startTimeStr} IS NULL) " +
            "AND (TransMsgTime <= #{endTimeStr} OR #{endTimeStr} IS NULL) " +
            "GROUP BY InnerReportState")
    List<Map<String, Object>> countDeliverStats(
            @Param("tableSuffix") String tableSuffix,
            @Param("sourAccount") String sourAccount,
            @Param("startTimeStr") String startTimeStr,
            @Param("endTimeStr") String endTimeStr
    );
}