package com.texcellsms.sms.mapper.eccom;

import com.texcellsms.sms.model.DTO.SubmitRecordDTO;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;

@Repository
@Mapper
public interface SubmitRecordMapper {

    // 分页查询（已修复：时间比较 + 状态过滤）
    @Select({
            "SELECT ",
            "    sb.SourAccount,",
            "    sb.SubmitMsgId,",
            "    sb.SubmitMsgTime,",
            "    sb.Caller,",
            "    sb.Callee,",
            "    sb.SM_Length,",
            "    sb.ChargeFee,",
            "    ROUND(IFNULL(CAST(sb.ChargeFee AS DECIMAL)/10000, 0), 4) as actualChargeFee,",
            "    sb.Short_Message as originalShortMessage,",
            "    sb.DCS as dcs,",
            "    sb.MCC as mcc,",
            "    sb.MNC as mnc,",
            "    fb.InnerReportState as fbInnerReportState,",
            "    fb.RecvDRTime as recvDRTime,",
            "    fb.InnerReportError as innerReportError ",
            "FROM Submit_Bill_${tableSuffix} sb ",
            "LEFT JOIN Fee_Bill_${tableSuffix} fb ON sb.SubmitMsgId = fb.RecvMsgId ",
            "WHERE sb.SourAccount = #{sourAccount} ",
            "AND sb.SubmitMsgTime >= #{startTimeStr} ",
            "AND sb.SubmitMsgTime <= #{endTimeStr} ",
            "AND (#{callee} = '' OR sb.Callee = #{callee}) ",
            "AND (#{mcc} = '' OR sb.MCC = #{mcc}) ",
            "AND (#{mnc} = '' OR sb.MNC = #{mnc}) ",
            "AND (#{innerReportState} = '' OR IFNULL(fb.InnerReportState, '') = #{innerReportState}) ",
            "AND (#{innerReportError} = '' OR IFNULL(fb.InnerReportError, '') = #{innerReportError}) ",
            "LIMIT #{pageSize} OFFSET #{offset}"
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

    // 导出查询
    @Select({
            "SELECT ",
            "    sb.SourAccount,",
            "    sb.SubmitMsgId,",
            "    sb.SubmitMsgTime,",
            "    sb.Caller,",
            "    sb.Callee,",
            "    sb.SM_Length,",
            "    sb.ChargeFee,",
            "    ROUND(IFNULL(CAST(sb.ChargeFee AS DECIMAL)/10000, 0), 4) as actualChargeFee,",
            "    sb.Short_Message as originalShortMessage,",
            "    sb.DCS as dcs,",
            "    sb.MCC as mcc,",
            "    sb.MNC as mnc,",
            "    fb.InnerReportState as fbInnerReportState,",
            "    fb.RecvDRTime as recvDRTime,",
            "    fb.InnerReportError as innerReportError ",
            "FROM Submit_Bill_${tableSuffix} sb ",
            "LEFT JOIN Fee_Bill_${tableSuffix} fb ON sb.SubmitMsgId = fb.RecvMsgId ",
            "WHERE sb.SourAccount = #{sourAccount} ",
            "AND sb.SubmitMsgTime >= #{startTimeStr} ",
            "AND sb.SubmitMsgTime <= #{endTimeStr} ",
            "AND (#{callee} = '' OR sb.Callee = #{callee}) ",
            "AND (#{mcc} = '' OR sb.MCC = #{mcc}) ",
            "AND (#{mnc} = '' OR sb.MNC = #{mnc}) ",
            "AND (#{innerReportState} = '' OR IFNULL(fb.InnerReportState, '') = #{innerReportState}) ",
            "AND (#{innerReportError} = '' OR IFNULL(fb.InnerReportError, '') = #{innerReportError}) "
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

    // 统计总数
    @Select({
            "SELECT COUNT(*) FROM Submit_Bill_${tableSuffix} ",
            "WHERE SourAccount = #{sourAccount} ",
            "AND SubmitMsgTime >= #{startTimeStr} ",
            "AND SubmitMsgTime <= #{endTimeStr} "
    })
    Long countSubmitTotal(
            @Param("tableSuffix") String tableSuffix,
            @Param("sourAccount") String sourAccount,
            @Param("startTimeStr") String startTimeStr,
            @Param("endTimeStr") String endTimeStr
    );

    // 送达统计
    @Select({
            "SELECT InnerReportState, COUNT(*) AS count ",
            "FROM Fee_Bill_${tableSuffix} ",
            "WHERE SourAccount = #{sourAccount} ",
            "AND TransMsgTime >= #{startTimeStr} ",
            "AND TransMsgTime <= #{endTimeStr} ",
            "GROUP BY InnerReportState"
    })
    List<Map<String, Object>> countDeliverStats(
            @Param("tableSuffix") String tableSuffix,
            @Param("sourAccount") String sourAccount,
            @Param("startTimeStr") String startTimeStr,
            @Param("endTimeStr") String endTimeStr
    );
}