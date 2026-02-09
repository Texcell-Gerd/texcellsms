package com.texcellsms.sms.mapper.prepay;

import com.texcellsms.sms.model.DTO.SmsQueueDetailItemDTO;
import com.texcellsms.sms.model.DTO.SmsQueueItemDTO;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 短信队列Mapper（注解式SQL，无XML）
 */
@Repository
@Mapper
public interface SmsQueueMapper {

    /**
     * 按秒分组查询近三天队列列表（分页）
     */
    @Select({
            "SELECT ",
            "    DATE_FORMAT(sb.SubmitMsgTime, '%Y/%m/%d %H:%i:%s') as submitMsgTime,",
            "    COUNT(*) as totalCount,",
            "    COUNT(*) as sendCount,",
            "    COUNT(*) as submitSuccessCount,",
            "    SUM(CASE WHEN fb.InnerReportState = 'DELIVRD' THEN 1 ELSE 0 END) as deliverSuccessCount,",
            "    SUM(CASE WHEN fb.InnerReportState != 'DELIVRD' OR fb.InnerReportState IS NULL THEN 1 ELSE 0 END) as deliverFailCount,",
            "    CASE ",
            "        WHEN sb.DCS='0' THEN CAST(UNHEX(sb.Short_Message) AS CHAR) ",
            "        WHEN sb.DCS='1' THEN CAST(CONVERT(UNHEX(sb.Short_Message) USING ASCII) AS CHAR)  ",
            "        WHEN sb.DCS='8' THEN CAST(CONVERT(UNHEX(sb.Short_Message) USING UCS2) AS CHAR) ",
            "        ELSE sb.Short_Message ",
            "    END as msg",
            "FROM Submit_Bill_${tableSuffix} sb",
            "LEFT JOIN Fee_Bill_${tableSuffix} fb ON sb.SubmitMsgId = fb.RecvMsgId",
            "WHERE 1=1",
            "  AND sb.SourAccount = #{sourAccount}",
            "  AND sb.SubmitMsgTime >= #{startTimeStr}",
            "  AND sb.SubmitMsgTime <= #{endTimeStr}",
            "GROUP BY DATE_FORMAT(sb.SubmitMsgTime, '%Y/%m/%d %H:%i:%s')",
            "ORDER BY sb.SubmitMsgTime DESC",
            "LIMIT #{offset}, #{pageSize}"
    })
    @Results({
            @Result(column = "submitMsgTime", property = "submitMsgTime"),
            @Result(column = "totalCount", property = "totalCount"),
            @Result(column = "sendCount", property = "sendCount"),
            @Result(column = "submitSuccessCount", property = "submitSuccessCount"),
            @Result(column = "deliverSuccessCount", property = "deliverSuccessCount"),
            @Result(column = "deliverFailCount", property = "deliverFailCount"),
            @Result(column = "msg", property = "msg")
    })
    List<SmsQueueItemDTO> queryQueueListBySecond(
            @Param("tableSuffix") String tableSuffix,
            @Param("sourAccount") String sourAccount,
            @Param("startTimeStr") String startTimeStr,
            @Param("endTimeStr") String endTimeStr,
            @Param("offset") Integer offset,
            @Param("pageSize") Integer pageSize
    );

    /**
     * 统计近三天队列总条数（用于计算分页）
     */
    @Select({
            "SELECT COUNT(DISTINCT DATE_FORMAT(sb.SubmitMsgTime, '%Y/%m/%d %H:%i:%s')) as total",
            "FROM Submit_Bill_${tableSuffix} sb",
            "LEFT JOIN Fee_Bill_${tableSuffix} fb ON sb.SubmitMsgId = fb.RecvMsgId",
            "WHERE 1=1",
            "  AND sb.SourAccount = #{sourAccount}",
            "  AND sb.SubmitMsgTime >= #{startTimeStr}",
            "  AND sb.SubmitMsgTime <= #{endTimeStr}"
    })
    Long countQueueTotal(
            @Param("tableSuffix") String tableSuffix,
            @Param("sourAccount") String sourAccount,
            @Param("startTimeStr") String startTimeStr,
            @Param("endTimeStr") String endTimeStr
    );

    /**
     * 查询指定秒级队列的明细数据
     */
    @Select({
            "SELECT ",
            "    sb.SubmitMsgId as submitMsgId,",
            "    sb.Callee as callee,",
            "    IFNULL(fb.InnerReportState, '已提交') as innerReportState,",
            "    fb.RecvDRTime as recvDRTime,",
            "    IFNULL(fb.InnerReportError, '') as innerReportError",
            "FROM Submit_Bill_${tableSuffix} sb",
            "LEFT JOIN Fee_Bill_${tableSuffix} fb ON sb.SubmitMsgId = fb.RecvMsgId",
            "WHERE 1=1",
            "  AND sb.SourAccount = #{sourAccount}",
            "  AND DATE_FORMAT(sb.SubmitMsgTime, '%Y/%m/%d %H:%i:%s') = #{targetTime}",
            "  AND (sb.Callee = #{callee} OR #{callee} IS NULL OR #{callee} = '')"
    })
    @Results({
            @Result(column = "submitMsgId", property = "submitMsgId"),
            @Result(column = "callee", property = "callee"),
            @Result(column = "innerReportState", property = "innerReportState"),
            @Result(column = "recvDRTime", property = "recvDRTime"),
            @Result(column = "innerReportError", property = "innerReportError")
    })
    List<SmsQueueDetailItemDTO> queryQueueDetailByTime(
            @Param("tableSuffix") String tableSuffix,
            @Param("sourAccount") String sourAccount,
            @Param("targetTime") String targetTime,
            @Param("callee") String callee // 精准匹配的接收号码参数
    );
}