package com.texcellsms.sms.mapper.prepay;

import com.texcellsms.sms.model.entity.ChargeLog;
import com.texcellsms.sms.model.entity.CustomerAccount;
import io.lettuce.core.dynamic.annotation.Param;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChargeLogMapper {

    // ========== 1. 查询prepay物理数据源的MngDB库=========
    @Select({
            "<script>",
            "SELECT UNID, CreateTime, UserName, Account, Amount, PrevBalance, PostBalance, Remark, AgentID ",
            "FROM MngDB.tbl_ChargeLog ",
            "WHERE Account = #{account} ",
            "  AND CreateTime BETWEEN #{startTime} AND #{endTime} ",
            "ORDER BY CreateTime DESC",
            "</script>"
    })
    List<ChargeLog> selectFromMngDB(
            @Param("account") String account,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime
    );

    @Select({
            "<script>",
            "SELECT CompanyID",
            "FROM MngDB.tbl_AccountInfo ",
            "WHERE AccountID = #{accountID} ",
            "</script>"
    })
    List<CustomerAccount> QueryUserParentAccount(
            @Param("accountID") String accountID
    );
}