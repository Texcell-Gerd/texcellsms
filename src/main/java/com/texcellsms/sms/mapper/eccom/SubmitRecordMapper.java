package com.texcellsms.sms.mapper.eccom;

import com.texcellsms.sms.model.DTO.SubmitRecordDTO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;

@Repository
public interface SubmitRecordMapper {

    /**
     * 检查表是否存在
     */
    @Select("SELECT COUNT(*) FROM information_schema.TABLES WHERE table_schema = DATABASE() AND table_name = #{tableName}")
    int checkTableExist(@Param("tableName") String tableName);

    @Select("SELECT SubmitMsgId, DestAccount, Caller, ChargeFee, SubmitMsgTime, Callee, "
            + "Short_Message as originalShortMessage, SM_Length, MCC, MNC, DCS, SourAccount "
            + "FROM Submit_Bill_${tableSuffix} "
            + "WHERE SourAccount = #{sourAccount} "
            + "AND SubmitMsgTime >= #{startTimeStr} "
            + "AND SubmitMsgTime <= #{endTimeStr} "
            + "AND (#{callee} = '' OR Callee = #{callee}) "
            + "AND (#{mcc} = '' OR MCC = #{mcc}) "
            + "AND (#{mnc} = '' OR MNC = #{mnc}) "
            + "AND ((#{destAccountCondition} = '') OR ( #{destAccountCondition} = 'success' AND DestAccount != 'hell' ) OR ( #{destAccountCondition} = 'fail' AND DestAccount = 'hell' ))")
    List<SubmitRecordDTO> querySubmitBill(
            @Param("tableSuffix") String tableSuffix,
            @Param("sourAccount") String sourAccount,
            @Param("startTimeStr") String startTimeStr,
            @Param("endTimeStr") String endTimeStr,
            @Param("callee") String callee,
            @Param("mcc") String mcc,
            @Param("mnc") String mnc,
            @Param("destAccountCondition") String destAccountCondition
    );

    @Select("SELECT "
            + "COUNT(CASE WHEN DestAccount != 'hell' THEN 1 END) AS successCount, "
            + "COUNT(CASE WHEN DestAccount = 'hell' THEN 1 END) AS failCount "
            + "FROM Submit_Bill_${tableSuffix} "
            + "WHERE SourAccount = #{sourAccount} "
            + "AND SubmitMsgTime >= #{startTimeStr} "
            + "AND SubmitMsgTime <= #{endTimeStr} "
            + "AND (#{callee} = '' OR Callee = #{callee}) "
            + "AND (#{mcc} = '' OR MCC = #{mcc}) "
            + "AND (#{mnc} = '' OR MNC = #{mnc}) "
            + "AND ((#{destAccountCondition} = '') OR ( #{destAccountCondition} = 'success' AND DestAccount != 'hell' ) OR ( #{destAccountCondition} = 'fail' AND DestAccount = 'hell' ))")
    Map<String, Object> countSubmitStats(
            @Param("tableSuffix") String tableSuffix,
            @Param("sourAccount") String sourAccount,
            @Param("startTimeStr") String startTimeStr,
            @Param("endTimeStr") String endTimeStr,
            @Param("callee") String callee,
            @Param("mcc") String mcc,
            @Param("mnc") String mnc,
            @Param("destAccountCondition") String destAccountCondition
    );

    /**
     * 【优化】去掉 RecvDRTime 时间条件，解决跨天查不到回执
     */
    @Select("SELECT RecvMsgId, InnerReportState, RecvDRTime "
            + "FROM Fee_Bill_${tableSuffix} "
            + "WHERE SourAccount = #{sourAccount} "
            + "AND (#{innerReportState} = '' OR InnerReportState = #{innerReportState}) "
            + "AND (#{callee} = '' OR OriginalCallee = #{callee}) "
            + "AND (#{mcc} = '' OR MCC = #{mcc}) "
            + "AND (#{mnc} = '' OR MNC = #{mnc})")
    List<Map<String, Object>> queryFeeBill(
            @Param("tableSuffix") String tableSuffix,
            @Param("sourAccount") String sourAccount,
            @Param("innerReportState") String innerReportState,
            @Param("callee") String callee,
            @Param("mcc") String mcc,
            @Param("mnc") String mnc
    );

    @Select("SELECT SubmitMsgId "
            + "FROM Submit_Bill_${tableSuffix} "
            + "WHERE SourAccount = #{sourAccount} "
            + "AND SubmitMsgTime >= #{startTimeStr} "
            + "AND SubmitMsgTime <= #{endTimeStr} "
            + "AND (#{callee} = '' OR Callee = #{callee}) "
            + "AND (#{mcc} = '' OR MCC = #{mcc}) "
            + "AND (#{mnc} = '' OR MNC = #{mnc}) "
            + "AND ((#{destAccountCondition} = '') OR ( #{destAccountCondition} = 'success' AND DestAccount != 'hell' ) OR ( #{destAccountCondition} = 'fail' AND DestAccount = 'hell' ))")
    List<String> listSubmitMsgIds(
            @Param("tableSuffix") String tableSuffix,
            @Param("sourAccount") String sourAccount,
            @Param("startTimeStr") String startTimeStr,
            @Param("endTimeStr") String endTimeStr,
            @Param("callee") String callee,
            @Param("mcc") String mcc,
            @Param("mnc") String mnc,
            @Param("destAccountCondition") String destAccountCondition
    );

    @Select("SELECT SubmitMsgId, SubmitMsgTime "
            + "FROM Submit_Bill_${tableSuffix} "
            + "WHERE SourAccount = #{sourAccount} "
            + "AND SubmitMsgTime >= #{startTimeStr} "
            + "AND SubmitMsgTime <= #{endTimeStr} "
            + "AND (#{callee} = '' OR Callee = #{callee}) "
            + "AND (#{mcc} = '' OR MCC = #{mcc}) "
            + "AND (#{mnc} = '' OR MNC = #{mnc}) "
            + "AND ((#{destAccountCondition} = '') OR ( #{destAccountCondition} = 'success' AND DestAccount != 'hell' ) OR ( #{destAccountCondition} = 'fail' AND DestAccount = 'hell' ))")
    List<Map<String, Object>> listSubmitMsgIdsWithTime(
            @Param("tableSuffix") String tableSuffix,
            @Param("sourAccount") String sourAccount,
            @Param("startTimeStr") String startTimeStr,
            @Param("endTimeStr") String endTimeStr,
            @Param("callee") String callee,
            @Param("mcc") String mcc,
            @Param("mnc") String mnc,
            @Param("destAccountCondition") String destAccountCondition
    );

    @Select({
            "<script>",
            "SELECT SubmitMsgId, DestAccount, Caller, ChargeFee, SubmitMsgTime, Callee, "
                    + "Short_Message as originalShortMessage, SM_Length, MCC, MNC, DCS, SourAccount "
                    + "FROM Submit_Bill_${tableSuffix} ",
            "WHERE SubmitMsgId IN ",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "ORDER BY SubmitMsgTime DESC",
            "</script>"
    })
    List<SubmitRecordDTO> listByIds(
            @Param("tableSuffix") String tableSuffix,
            @Param("ids") List<String> ids
    );

}