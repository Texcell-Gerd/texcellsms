package com.texcellsms.sms.mapper.webportal;

import com.texcellsms.sms.model.entity.FeatureModule;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@Mapper
public interface FeatureModuleMapper {

    /**
     * 查询全部模块（管理端）
     */
    @Select("SELECT id, module_code AS moduleCode, module_name AS moduleName, " +
            "module_type AS moduleType, enabled, sort_order AS sortOrder, remark, " +
            "create_time AS createTime, update_time AS updateTime " +
            "FROM tbl_feature_module ORDER BY sort_order ASC, id ASC")
    List<FeatureModule> selectAll();

    /**
     * 查询所有已启用的模块（客户端判断用）
     */
    @Select("SELECT id, module_code AS moduleCode, module_name AS moduleName, " +
            "module_type AS moduleType, enabled, sort_order AS sortOrder, remark, " +
            "create_time AS createTime, update_time AS updateTime " +
            "FROM tbl_feature_module WHERE enabled = 1 ORDER BY sort_order ASC, id ASC")
    List<FeatureModule> selectEnabledAll();

    /**
     * 按编码查单个模块
     */
    @Select("SELECT id, module_code AS moduleCode, module_name AS moduleName, " +
            "module_type AS moduleType, enabled, sort_order AS sortOrder, remark, " +
            "create_time AS createTime, update_time AS updateTime " +
            "FROM tbl_feature_module WHERE module_code = #{moduleCode}")
    FeatureModule selectByCode(@Param("moduleCode") String moduleCode);

    /**
     * 新增
     */
    @Insert("INSERT INTO tbl_feature_module " +
            "(module_code, module_name, module_type, enabled, sort_order, remark) " +
            "VALUES (#{moduleCode}, #{moduleName}, #{moduleType}, #{enabled}, #{sortOrder}, #{remark})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(FeatureModule m);

    /**
     * 更新
     */
    @Update("UPDATE tbl_feature_module SET " +
            "module_name = #{moduleName}, " +
            "module_type = #{moduleType}, " +
            "enabled     = #{enabled}, " +
            "sort_order  = #{sortOrder}, " +
            "remark      = #{remark} " +
            "WHERE id = #{id}")
    int update(FeatureModule m);

    /**
     * 启/禁用
     */
    @Update("UPDATE tbl_feature_module SET enabled = #{enabled} WHERE id = #{id}")
    int toggleEnabled(@Param("id") Long id, @Param("enabled") Integer enabled);

    /**
     * 删除
     */
    @Delete("DELETE FROM tbl_feature_module WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}