package com.texcellsms.sms.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class SubTaskService {

    // 注入专属于 WEBPortal 的 JdbcTemplate
    @Autowired
    @Qualifier("webPortalJdbcTemplate")
    private JdbcTemplate webPortalJdbcTemplate;

    /**
     * 查询子任务列表
     *
     * @param taskId     母任务ID（可为 null 或空）
     * @param subAccount 客户账号（可为 null 或空，空则查全部）
     * @param limit      查询条数限制（如果 <=0，存储过程会默认设为 500）
     * @return 结果集列表
     */
    public List<Map<String, Object>> querySubTasks(String taskId, String subAccount, int limit) {
        String sql = "CALL prc_QueryAPISubTask(?, ?, ?)";
        // 调用存储过程，JdbcTemplate 会自动把结果集封装成 List<Map<String,Object>>
        return webPortalJdbcTemplate.queryForList(sql, taskId, subAccount, limit);
    }

    /**
     * 操作子任务（暂停、恢复、取消）
     *
     * @param action   操作类型（pause/resume/cancel）
     * @param taskId   父任务ID
     * @param subIndex 子任务序号（<=0 表示操作该任务下的所有符合条件的子任务）
     * @param remark   操作备注
     * @return 受影响的行数
     */
    public int operateSubTask(String action, String taskId, int subIndex, String remark) {
        String sql;
        // 根据前端传来的 action 匹配对应的存储过程
        if ("pause".equalsIgnoreCase(action)) {
            sql = "CALL prc_PauseAPISubTask(?, ?, ?)";
        } else if ("resume".equalsIgnoreCase(action)) {
            sql = "CALL prc_ResumeAPISubTask(?, ?, ?)";
        } else if ("cancel".equalsIgnoreCase(action)) {
            sql = "CALL prc_CancelAPISubTask(?, ?, ?)";
        } else {
            throw new IllegalArgumentException("不支持的操作类型: " + action);
        }

        try {
            // 直接执行存储过程，利用存储过程的覆盖逻辑更新备注
            List<Map<String, Object>> result = webPortalJdbcTemplate.queryForList(sql, taskId, subIndex, remark);

            // 解析并返回受影响的行数
            if (result != null && !result.isEmpty() && result.get(0).containsKey("Affected")) {
                Object affectedObj = result.get(0).get("Affected");
                if (affectedObj instanceof Number) {
                    return ((Number) affectedObj).intValue();
                }
            }
            return 0;
        } catch (Exception e) {
            throw new RuntimeException("操作子任务失败: " + e.getMessage(), e);
        }
    }
}