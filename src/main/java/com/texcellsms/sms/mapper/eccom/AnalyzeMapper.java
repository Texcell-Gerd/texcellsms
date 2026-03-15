package com.texcellsms.sms.mapper.eccom;

import com.texcellsms.sms.model.DTO.AnalyzeDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
@Mapper
public interface AnalyzeMapper {

    @Select({
            "<script>",
            "SELECT ",
            "    ReportTime    AS submitTime,",
            "    MCC           AS mcc,",
            "    MNC           AS mnc,",
            "    SubmitCount   AS submitCount,",
            "    ReportAccount     AS succCount,",
            "    FailCount     AS failCount,",
            "    ExpireCount   AS expireCount,",
            "    NilChargeCount AS nilChargeCount,",
            "    ReportTime    AS updateTime,",
            "    TotalCost     AS totalCost ",
            "FROM MngDB.tbl_RefundSum ",
            "WHERE ReportAccount = #{sourAccount} ",
            "AND ReportTime BETWEEN #{start} AND #{end} ",
            "<if test='mcc != null and mcc != \"\"'>AND MCC = #{mcc}</if>",
            "<if test='mnc != null and mnc != \"\"'>AND MNC = #{mnc}</if>",
            "ORDER BY ReportTime DESC",
            "LIMIT #{offset}, #{pageSize}",
            "</script>"
    })
    List<AnalyzeDTO> selectAnalyzePage(
            @Param("sourAccount") String sourAccount,
            @Param("start") String start,
            @Param("end") String end,
            @Param("mcc") String mcc,
            @Param("mnc") String mnc,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Select({
            "<script>",
            "SELECT COUNT(1) FROM MngDB.tbl_RefundSum ",
            "WHERE ReportAccount = #{sourAccount} ",
            "AND ReportTime BETWEEN #{start} AND #{end} ",
            "<if test='mcc != null and mcc != \"\"'>AND MCC = #{mcc}</if>",
            "<if test='mnc != null and mnc != \"\"'>AND MNC = #{mnc}</if>",
            "</script>"
    })
    int countAnalyze(
            @Param("sourAccount") String sourAccount,
            @Param("start") String start,
            @Param("end") String end,
            @Param("mcc") String mcc,
            @Param("mnc") String mnc
    );
}