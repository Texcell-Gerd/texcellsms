package com.texcellsms.sms.mapper.eccom;

import com.texcellsms.sms.model.DTO.PItemDTO;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@Mapper
public interface AnalyzeMapper {

    @Select({
            "SELECT ",
            "SubmitTime AS ReportDate,",
            "ReportTime AS MNC,",
            "SubmitTime AS SubmitCount,",
            "SuccCount AS DelivrdCount,",
            "FailCount AS FailCount,",
            "ExpireCount AS ExpireCount,",
            "NilChargeCount AS NilChargeCount,",
            "ReportTime AS UpdateTime,",
            "ReportTime AS MCC,",
            "TotalCost AS Cost",
            "FROM MngDB.tbl_RefundSum ",
            "WHERE 1 = 1",
            "<if test='startTimeStr != null and startTimeStr != \"\"'>",
                "AND SubmitTime >= #{startTimeStr}",
            "  </if>",
            "<if test='endTimeStr != null and endTimeStr != \"\"'>",
                "AND SubmitTime <= #{endTimeStr}",
            "  </if>",
            "<if test='mcc != null and mcc != \"\"'>",
                "AND MCC = #{mcc}",
            "  </if>",
            "<if test='mnc != null and mnc != \"\"'>",
                "AND MNC = #{mnc}",
            "  </if>",
            "LIMIT #{offset}, #{pageSize}",
    })
    @Results({
            @Result(column = "ReportDate", property = "ReportDate"),
            @Result(column = "MCC", property = "MCC"),
            @Result(column = "MNC", property = "MNC"),
            @Result(column = "SubmitCount", property = "SubmitCount"),
            @Result(column = "DelivrdCount", property = "DelivrdCount"),
            @Result(column = "FailCount", property = "FailCount"),
            @Result(column = "ExpireCount", property = "ExpireCount"),
            @Result(column = "NilChargeCount", property = "NilChargeCount"),
            @Result(column = "UpdateTime", property = "UpdateTime"),
            @Result(column = "Cost", property = "Cost"),
    })
    List<PItemDTO> queryAnalyzeData(
            @Param("sourAccount") String sourAccount,
            @Param("startTimeStr") String startTimeStr,
            @Param("endTimeStr") String endTimeStr,
            @Param("mcc") String mcc,
            @Param("mnc") String mnc,
            @Param("pageSize") Integer pageSize,
            @Param("offset") Integer offset
    );
}
