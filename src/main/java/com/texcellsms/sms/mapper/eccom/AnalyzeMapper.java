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

    // ====================== 列表分页 ======================
    // ====================== 列表分页 ======================
    @Select({
            "SELECT",
            "    SubmitTime    AS submitTime,",
            "    MCC           AS mcc,",
            "    MNC           AS mnc,",
            "    SubmitCount   AS submitCount,",
            "    ReportAccount     AS sourAccount,",
            "    SuccCount     AS succCount,",
            "    FailCount     AS failCount,",
            "    ExpireCount   AS expireCount,",
            "    NilChargeCount AS nilChargeCount,",
            "    ReportTime    AS updateTime,",
            "    TotalCost     AS totalCost ",
            "FROM MngDB.tbl_RefundSum ",
            "WHERE ReportAccount = #{sourAccount} ",
            "AND SubmitTime >= #{start} ",
            "AND SubmitTime <= #{end} ",
            "AND (#{mcc} IS NULL OR #{mcc} = '' OR MCC = #{mcc}) ",
            "AND (#{mnc} IS NULL OR #{mnc} = '' OR MNC = #{mnc}) ",
            "ORDER BY SubmitTime DESC, ReportTime DESC ",
            "LIMIT #{offset}, #{pageSize}"
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

    // ====================== 统计总数 ======================
    @Select({
            "SELECT COUNT(1) FROM MngDB.tbl_RefundSum ",
            "WHERE ReportAccount = #{sourAccount} ",
            "AND SubmitTime BETWEEN #{start} AND #{end} ",
            "AND (#{mcc} IS NULL OR #{mcc} = '' OR MCC = #{mcc}) ",
            "AND (#{mnc} IS NULL OR #{mnc} = '' OR MNC = #{mnc}) "
    })
    int countAnalyze(
            @Param("sourAccount") String sourAccount,
            @Param("start") String start,
            @Param("end") String end,
            @Param("mcc") String mcc,
            @Param("mnc") String mnc
    );

    // ====================== 图表统计======================
    @Select({
            "SELECT",
            "    SubmitTime    AS submitTime,",
            "    SubmitCount   AS submitCount,",
            "    TotalCost     AS totalCost ",
            "FROM MngDB.tbl_RefundSum ",
            "WHERE ReportAccount = #{sourAccount} ",
            "AND SubmitTime >= #{start} ",
            "AND SubmitTime <= #{end} ",
            "AND (#{mcc} IS NULL OR #{mcc} = '' OR MCC = #{mcc}) "
    })
    List<AnalyzeDTO> selectAnalyzeList(
            @Param("sourAccount") String sourAccount,
            @Param("start") String start,
            @Param("end") String end,
            @Param("mcc") String mcc,
            @Param("mnc") String mnc
    );

}