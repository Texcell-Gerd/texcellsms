package com.texcellsms.sms.mapper.prepay;

import com.texcellsms.sms.model.entity.ChargeLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChargeLogMapper {

    // ========== 1. 查询prepay物理数据源的MngDB库=========
    @Select("SELECT * FROM MngDB.tbl_ChargeLog WHERE Account = #{account}")
    List<ChargeLog> selectFromMngDB(String account);


//    @Select("SELECT * FROM CDR_DR.tbl_ChargeLog WHERE Account = #{account}")
//    List<ChargeLog> selectFromCDR_DR(String account);
//

//    @Select("SELECT * FROM ${dbName}.tbl_ChargeLog WHERE Account = #{account}")
//    List<ChargeLog> selectFromDb(@Param("dbName") String dbName, @Param("account") String account);
}