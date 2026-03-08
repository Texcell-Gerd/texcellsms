package com.texcellsms.sms.mapper.prepay;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

@Repository
@Mapper
public interface SmsNumberFileMapper {

    @Insert("INSERT INTO tbl_SmsNumberFile " +
            "(preTaskId, subAccount, fileName, filePath, totalCount) " +
            "VALUES " +
            "(#{preTaskId}, #{subAccount}, #{fileName}, #{filePath}, #{totalCount})")
    int insertNumberFileRecord(
            @Param("preTaskId") String preTaskId,
            @Param("subAccount") String subAccount,
            @Param("fileName") String fileName,
            @Param("filePath") String filePath,
            @Param("totalCount") int totalCount
    );
}