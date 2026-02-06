package com.texcellsms.sms.mapper.prepay;

import com.texcellsms.sms.model.DTO.SubmitRecordDTO;
import org.apache.ibatis.annotations.Mapper; // 核心：添加@Mapper注解
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.type.JdbcType;
import java.util.Date;
import java.util.List;

@Mapper
public interface SubmitRecordMapper {

    @Results({
            @Result(column = "SubmitMsgTime", property = "SubmitMsgTime", jdbcType = JdbcType.VARCHAR),
            @Result(column = "Caller", property = "Caller", jdbcType = JdbcType.VARCHAR),
            @Result(column = "Callee", property = "Callee", jdbcType = JdbcType.VARCHAR),
            @Result(column = "SM_Length", property = "SM_Length", jdbcType = JdbcType.VARCHAR),
            @Result(column = "ChargeFee", property = "ChargeFee", jdbcType = JdbcType.VARCHAR),
            @Result(column = "Short_Message", property = "Short_Message", jdbcType = JdbcType.VARCHAR),
            @Result(column = "MCC", property = "MCC", jdbcType = JdbcType.VARCHAR),
            @Result(column = "MNC", property = "MNC", jdbcType = JdbcType.VARCHAR),
            @Result(column = "SubmitMsgId", property = "SubmitMsgId", jdbcType = JdbcType.VARCHAR),
            @Result(column = "InnerReportState", property = "InnerReportState", jdbcType = JdbcType.VARCHAR),
            @Result(column = "InnerReportError", property = "InnerReportError", jdbcType = JdbcType.VARCHAR),
            @Result(column = "RecvDRTime", property = "RecvDRTime", jdbcType = JdbcType.VARCHAR),
            @Result(column = "SourAccount", property = "SourAccount", jdbcType = JdbcType.VARCHAR)
    })
    @Select({
            "<script>",
            "<foreach collection='tableSuffixList' item='suffix' separator='UNION ALL'>",
            "SELECT",
            "sb.SubmitMsgTime,",
            "sb.Caller,",
            "sb.Callee,",
            "sb.SM_Length,",
            "sb.ChargeFee,",
            "CASE WHEN sb.DCS='0' THEN CAST(UNHEX(sb.Short_Message) AS CHAR)",
            "WHEN sb.DCS='1' THEN CAST(CONVERT(UNHEX(sb.Short_Message) USING 'ASCII') AS CHAR)",
            "WHEN sb.DCS='8' THEN CAST(CONVERT(UNHEX(sb.Short_Message) USING 'UCS2') AS CHAR)",
            "END AS Short_Message,",
            "sb.MCC,",
            "sb.MNC,",
            "sb.SubmitMsgId,",
            "COALESCE(fb.InnerReportState, '已提交') AS InnerReportState,",
            "fb.InnerReportError,",
            "fb.RecvDRTime,",
            "sb.SourAccount",
            "FROM CDR_DB.Submit_Bill_${suffix} sb",
            "LEFT JOIN CDR_DB.Fee_Bill_${suffix} fb ON sb.SubmitMsgId = fb.RecvMsgId",
            "WHERE UPPER(sb.SourAccount) = UPPER(#{sourAccount})",
            "AND STR_TO_DATE(sb.SubmitMsgTime, '%Y/%m/%d %H:%i:%s') &gt;= #{startTime}",
            "AND STR_TO_DATE(sb.SubmitMsgTime, '%Y/%m/%d %H:%i:%s') &lt; #{endTime}",
            "<if test='callee != null and callee != &quot;&quot;'>",
            "AND sb.Callee = #{callee}",
            "</if>",
            "<if test='mcc != null and mcc != &quot;&quot;'>",
            "AND sb.MCC = #{mcc}",
            "</if>",
            "<if test='mnc != null and mnc != &quot;&quot;'>",
            "AND sb.MNC = #{mnc}",
            "</if>",
            "<if test='innerReportState != null and innerReportState != &quot;&quot;'>",
            "AND COALESCE(fb.InnerReportState, '已提交') = #{innerReportState}",
            "</if>",
            "<if test='innerReportError != null and innerReportError != &quot;&quot;'>",
            "AND fb.InnerReportError = #{innerReportError}",
            "</if>",
            "</foreach>",
            "ORDER BY STR_TO_DATE(SubmitMsgTime, '%Y/%m/%d %H:%i:%s') DESC",
            "</script>"
    })
    List<SubmitRecordDTO> querySubmitRecords(
            @Param("tableSuffixList") List<String> tableSuffixList,
            @Param("startTime") Date startTime,
            @Param("endTime") Date endTime,
            @Param("sourAccount") String sourAccount,
            @Param("callee") String callee,
            @Param("mcc") String mcc,
            @Param("mnc") String mnc,
            @Param("innerReportState") String innerReportState,
            @Param("innerReportError") String innerReportError
    );
}