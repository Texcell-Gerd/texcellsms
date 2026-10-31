package com.texcellsms.sms.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class SubTaskService {

    @Autowired
    @Qualifier("webPortalJdbcTemplate")
    private JdbcTemplate webPortalJdbcTemplate;

    public List<Map<String, Object>> querySubTasks(String taskId, String subAccount, int limit) {
        String sql = "CALL prc_QueryAPISubTask(?, ?, ?)";
        return webPortalJdbcTemplate.queryForList(sql, taskId, subAccount, limit);
    }

    public int operateSubTask(String action, String taskId, int subIndex, String remark) {
        String sql;
        if ("pause".equalsIgnoreCase(action)) {
            sql = "CALL prc_PauseAPISubTask(?, ?, ?)";
        } else if ("resume".equalsIgnoreCase(action)) {
            sql = "CALL prc_ResumeAPISubTask(?, ?, ?)";
        } else if ("cancel".equalsIgnoreCase(action)) {
            sql = "CALL prc_CancelAPISubTask(?, ?, ?)";
        } else {
            throw new IllegalArgumentException("Unsupported action type: " + action);
        }

        try {
            List<Map<String, Object>> result = webPortalJdbcTemplate.queryForList(sql, taskId, subIndex, remark);

            if (result != null && !result.isEmpty() && result.get(0).containsKey("Affected")) {
                Object affectedObj = result.get(0).get("Affected");
                if (affectedObj instanceof Number) {
                    return ((Number) affectedObj).intValue();
                }
            }
            return 0;
        } catch (Exception e) {
            throw new RuntimeException("Failed to operate subtask: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> queryGroupedTasks(String subAccount, String taskId, int pageNum, int pageSize) {
        int offset = (pageNum - 1) * pageSize;

        StringBuilder sql = new StringBuilder(
                "SELECT " +
                        "  APITaskId, " +
                        "  MAX(SubAccount) AS SubAccount, " +
                        "  COUNT(*) AS SubTotalCount, " +
                        "  SUM(CalleeCount) AS TotalCalleeCount, " +
                        "  SUM(CASE WHEN SubStatus = 0 THEN 1 ELSE 0 END) AS PendingCount, " +
                        "  SUM(CASE WHEN SubStatus = 1 THEN 1 ELSE 0 END) AS ProcessingCount, " +
                        "  SUM(CASE WHEN SubStatus = 2 THEN 1 ELSE 0 END) AS PausedCount, " +
                        "  SUM(CASE WHEN SubStatus = 3 THEN 1 ELSE 0 END) AS CanceledCount " +
                        "FROM tbl_APISubTask " +
                        "WHERE 1=1 "
        );

        List<Object> params = new java.util.ArrayList<>();

        if (subAccount != null && !subAccount.trim().isEmpty()) {
            sql.append("AND SubAccount = ? ");
            params.add(subAccount);
        }
        if (taskId != null && !taskId.trim().isEmpty()) {
            sql.append("AND APITaskId = ? ");
            params.add(taskId);
        }

        sql.append("GROUP BY APITaskId ORDER BY APITaskId DESC LIMIT ?, ?");
        params.add(offset);
        params.add(pageSize);

        StringBuilder countSql = new StringBuilder("SELECT COUNT(DISTINCT APITaskId) FROM tbl_APISubTask WHERE 1=1 ");
        List<Object> countParams = new java.util.ArrayList<>();
        if (subAccount != null && !subAccount.trim().isEmpty()) {
            countSql.append("AND SubAccount = ? ");
            countParams.add(subAccount);
        }
        if (taskId != null && !taskId.trim().isEmpty()) {
            countSql.append("AND APITaskId = ? ");
            countParams.add(taskId);
        }

        try {
            List<Map<String, Object>> list = webPortalJdbcTemplate.queryForList(sql.toString(), params.toArray());
            Long total = webPortalJdbcTemplate.queryForObject(countSql.toString(), Long.class, countParams.toArray());

            Map<String, Object> result = new java.util.HashMap<>();
            result.put("records", list);
            result.put("total", total != null ? total : 0);
            result.put("pageNum", pageNum);
            result.put("pageSize", pageSize);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to query grouped subtasks: " + e.getMessage(), e);
        }
    }
}