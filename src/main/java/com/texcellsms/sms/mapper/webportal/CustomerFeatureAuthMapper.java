package com.texcellsms.sms.mapper.webportal;

import com.texcellsms.sms.model.entity.CustomerFeatureAuth;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@Mapper
public interface CustomerFeatureAuthMapper {

    /**
     * 查询某账号所有已启用的模块编码
     */
    @Select("SELECT module_code FROM tbl_customer_feature_auth " +
            "WHERE sub_account = #{subAccount} AND enabled = 1")
    List<String> selectEnabledCodesByAccount(@Param("subAccount") String subAccount);

    /**
     * 检查某账号是否有某模块权限
     */
    @Select("SELECT COUNT(*) FROM tbl_customer_feature_auth " +
            "WHERE sub_account = #{subAccount} AND module_code = #{moduleCode} AND enabled = 1")
    int checkAuth(@Param("subAccount") String subAccount,
                  @Param("moduleCode") String moduleCode);

    /**
     * 查询某模块下所有授权的客户
     */
    @Select("SELECT id, sub_account AS subAccount, module_code AS moduleCode, " +
            "enabled, create_time AS createTime, update_time AS updateTime " +
            "FROM tbl_customer_feature_auth " +
            "WHERE module_code = #{moduleCode} ORDER BY sub_account ASC")
    List<CustomerFeatureAuth> selectByModuleCode(@Param("moduleCode") String moduleCode);

    /**
     * 新增或更新授权（MySQL 唯一键 sub_account + module_code）
     */
    @Insert("INSERT INTO tbl_customer_feature_auth (sub_account, module_code, enabled) " +
            "VALUES (#{subAccount}, #{moduleCode}, #{enabled}) " +
            "ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), update_time = CURRENT_TIMESTAMP")
    int upsert(CustomerFeatureAuth auth);

    /**
     * 删除授权
     */
    @Delete("DELETE FROM tbl_customer_feature_auth " +
            "WHERE sub_account = #{subAccount} AND module_code = #{moduleCode}")
    int deleteAuth(@Param("subAccount") String subAccount,
                   @Param("moduleCode") String moduleCode);
}