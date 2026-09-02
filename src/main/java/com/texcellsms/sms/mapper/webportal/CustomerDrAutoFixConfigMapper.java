package com.texcellsms.sms.mapper.webportal;

import com.texcellsms.sms.model.entity.CustomerDrAutoFixConfig;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface CustomerDrAutoFixConfigMapper {

    /**
     * 插入配置 - 插入到 tbl_customer_dr_auto_fix_config 表
     */
    @Insert("INSERT INTO tbl_customer_dr_auto_fix_config (" +
            "sub_account, enabled, timeout_minutes, max_retry_count, " +
            "schedule_cron, schedule_timezone, min_total_count, max_pending_count, " +
            "notify_before_fix, notify_after_fix, notify_webhook, remark, created_by) " +
            "VALUES (#{subAccount}, #{enabled}, #{timeoutMinutes}, #{maxRetryCount}, " +
            "#{scheduleCron}, #{scheduleTimezone}, #{minTotalCount}, #{maxPendingCount}, " +
            "#{notifyBeforeFix}, #{notifyAfterFix}, #{notifyWebhook}, #{remark}, #{createdBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CustomerDrAutoFixConfig config);

    /**
     * 更新配置
     */
    @Update("UPDATE tbl_customer_dr_auto_fix_config SET " +
            "enabled = #{enabled}, " +
            "timeout_minutes = #{timeoutMinutes}, " +
            "max_retry_count = #{maxRetryCount}, " +
            "schedule_cron = #{scheduleCron}, " +
            "schedule_timezone = #{scheduleTimezone}, " +
            "min_total_count = #{minTotalCount}, " +
            "max_pending_count = #{maxPendingCount}, " +
            "notify_before_fix = #{notifyBeforeFix}, " +
            "notify_after_fix = #{notifyAfterFix}, " +
            "notify_webhook = #{notifyWebhook}, " +
            "remark = #{remark}, " +
            "updated_by = #{updatedBy}, " +
            "updated_time = NOW() " +
            "WHERE id = #{id}")
    int update(CustomerDrAutoFixConfig config);

    /**
     * 根据ID删除配置
     */
    @Delete("DELETE FROM tbl_customer_dr_auto_fix_config WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    /**
     * 根据客户账号查询配置
     */
    @Select("SELECT * FROM tbl_customer_dr_auto_fix_config WHERE sub_account = #{subAccount}")
    CustomerDrAutoFixConfig selectBySubAccount(@Param("subAccount") String subAccount);

    /**
     * 根据ID查询配置
     */
    @Select("SELECT * FROM tbl_customer_dr_auto_fix_config WHERE id = #{id}")
    CustomerDrAutoFixConfig selectById(@Param("id") Long id);

    /**
     * 查询所有启用的配置
     */
    @Select("SELECT * FROM tbl_customer_dr_auto_fix_config WHERE enabled = 1 ORDER BY sub_account")
    List<CustomerDrAutoFixConfig> selectAllEnabled();

    /**
     * 分页查询所有配置
     */
    @Select("<script>" +
            "SELECT * FROM tbl_customer_dr_auto_fix_config " +
            "<where>" +
            "<if test='subAccount != null and subAccount != \"\"'>" +
            "  AND sub_account LIKE CONCAT('%', #{subAccount}, '%')" +
            "</if>" +
            "<if test='enabled != null'>" +
            "  AND enabled = #{enabled}" +
            "</if>" +
            "</where>" +
            "ORDER BY sub_account " +
            "LIMIT #{offset}, #{limit}" +
            "</script>")
    List<CustomerDrAutoFixConfig> selectPage(@Param("offset") int offset,
                                             @Param("limit") int limit,
                                             @Param("subAccount") String subAccount,
                                             @Param("enabled") Integer enabled);

    /**
     * 统计配置总数
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM tbl_customer_dr_auto_fix_config " +
            "<where>" +
            "<if test='subAccount != null and subAccount != \"\"'>" +
            "  AND sub_account LIKE CONCAT('%', #{subAccount}, '%')" +
            "</if>" +
            "<if test='enabled != null'>" +
            "  AND enabled = #{enabled}" +
            "</if>" +
            "</where>" +
            "</script>")
    long count(@Param("subAccount") String subAccount,
               @Param("enabled") Integer enabled);

    /**
     * 获取所有已启用配置的客户账号列表（用于过滤DR扫描）
     */
    @Select("SELECT sub_account FROM tbl_customer_dr_auto_fix_config WHERE enabled = 1")
    List<String> selectAllEnabledSubAccounts();
}