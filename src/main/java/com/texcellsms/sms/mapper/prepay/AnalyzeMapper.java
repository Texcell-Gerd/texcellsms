package com.texcellsms.sms.mapper.prepay;

import com.texcellsms.sms.model.DTO.AnalyzeDTO;
import com.texcellsms.sms.model.DTO.PItemDTO;
import com.texcellsms.sms.model.DTO.SubmitRecordDTO;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

@Repository
@Mapper
public interface AnalyzeMapper {

    @Select({
            "待实现SQL"
    })
    @Results({
            @Result(column = "SourAccount", property = "sourAccount"),
            @Result(column = "SubmitMsgId", property = "submitMsgId"),
            @Result(column = "SubmitMsgTime", property = "submitMsgTime"),
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
