package com.texcellsms.sms.mapper.eccom;

import com.texcellsms.sms.model.entity.ChargeLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * eccom 充值日志Mapper
 */
@Mapper
public interface EccomChargeLogMapper {

    // ECCOM充值日志查询
    @Select("SELECT * FROM MngDB.tbl_ChargeLog WHERE Account = #{account}")
    List<ChargeLog> selectFromMngDB(String account);


    // @Select("SELECT * FROM EccomDB.tbl_ChargeLog WHERE Account = #{account}")
    // List<ChargeLog> selectFromEccomDB(String account);
}