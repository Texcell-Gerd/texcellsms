package com.texcellsms.sms.mapper.prepay;


import com.texcellsms.sms.model.DTO.SendRecordDTO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.type.JdbcType;
import java.util.Date;
import java.util.List;

//发送记录接口
public interface SendRecordMapper {

    /**
     * 绝杀版：
     * 1. @Results手动指定所有字段类型，强制时间字段为String
     * 2. WHERE条件转换时间（匹配查询）
     * 3. 按转换后的时间倒序排序
     * 4. 转义XML特殊字符
     * 5. 关闭自动映射，完全手动控制
     */
    @Results({
            @Result(column = "TransMsgTime", property = "TransMsgTime", jdbcType = JdbcType.VARCHAR),
            @Result(column = "OriginalCaller", property = "OriginalCaller", jdbcType = JdbcType.VARCHAR),
            @Result(column = "RecvMsgId", property = "RecvMsgId", jdbcType = JdbcType.VARCHAR),
            @Result(column = "OriginalCallee", property = "OriginalCallee", jdbcType = JdbcType.VARCHAR),
            @Result(column = "RecvDRTime", property = "RecvDRTime", jdbcType = JdbcType.VARCHAR),
            @Result(column = "MsgContent", property = "MsgContent", jdbcType = JdbcType.VARCHAR),
            @Result(column = "MCC", property = "MCC", jdbcType = JdbcType.VARCHAR),
            @Result(column = "MNC", property = "MNC", jdbcType = JdbcType.VARCHAR),
            @Result(column = "InnerReportState", property = "InnerReportState", jdbcType = JdbcType.VARCHAR),
            @Result(column = "InnerReportError", property = "InnerReportError", jdbcType = JdbcType.VARCHAR),
            @Result(column = "SourAccount", property = "SourAccount", jdbcType = JdbcType.VARCHAR),
            @Result(column = "ChargeFlag", property = "ChargeFlag", jdbcType = JdbcType.INTEGER)
    })
    @Select({
            "<script>",
            "<foreach collection='tableSuffixList' item='suffix' separator='UNION ALL'>",
            "SELECT",
            "fb.TransMsgTime,",
            "fb.OriginalCaller,",
            "fb.RecvMsgId,",
            "fb.OriginalCallee,",
            "fb.RecvDRTime,",
            "fb.MsgContent,",
            "fb.MCC,",
            "fb.MNC,",
            "fb.InnerReportState,",
            "fb.InnerReportError,",
            "fb.SourAccount,",
            "tsb.ChargeFlag",
            "FROM CDR_DB.Fee_Bill_${suffix} fb",
            "LEFT JOIN CDR_DB.TransSubmit_Bill_${suffix} tsb ON fb.InnerMsgId = tsb.InnerMsgId",
            "WHERE fb.SourAccount = #{sourAccount}",
            "AND STR_TO_DATE(fb.TransMsgTime, '%Y/%m/%d %H:%i:%s') &gt;= #{startTime}",
            "AND STR_TO_DATE(fb.TransMsgTime, '%Y/%m/%d %H:%i:%s') &lt; #{endTime}",
            "<if test='originalCallee != null and originalCallee != &quot;&quot;'>",
            "AND fb.OriginalCallee = #{originalCallee}",
            "</if>",
            "<if test='mcc != null and mcc != &quot;&quot;'>",
            "AND fb.MCC = #{mcc}",
            "</if>",
            "<if test='mnc != null and mnc != &quot;&quot;'>",
            "AND fb.MNC = #{mnc}",
            "</if>",
            "<if test='innerReportState != null and innerReportState != &quot;&quot;'>",
            "AND fb.InnerReportState = #{innerReportState}",
            "</if>",
            "<if test='innerReportError != null and innerReportError != &quot;&quot;'>",
            "AND fb.InnerReportError = #{innerReportError}",
            "</if>",
            "</foreach>",
            "ORDER BY STR_TO_DATE(TransMsgTime, '%Y/%m/%d %H:%i:%s') DESC",
            "</script>"
    })
    List<SendRecordDTO> querySendRecords(
            @Param("tableSuffixList") List<String> tableSuffixList,
            @Param("startTime") Date startTime,
            @Param("endTime") Date endTime,
            @Param("sourAccount") String sourAccount,
            @Param("originalCallee") String originalCallee,
            @Param("mcc") String mcc,
            @Param("mnc") String mnc,
            @Param("innerReportState") String innerReportState,
            @Param("innerReportError") String innerReportError
    );
}