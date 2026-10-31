package com.texcellsms.sms.controller;

import com.texcellsms.sms.service.SubTaskService;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.AjaxResult;
import com.texcellsms.sms.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/eccom/subtask")
public class ClientSubTaskController {

    @Autowired
    private SubTaskService subTaskService;

    /**
     * 客户端：查询自己的子任务列表（聚合分组）
     */
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) String taskId,
                           @RequestParam(defaultValue = "1") int pageNum,
                           @RequestParam(defaultValue = "10") int pageSize) {
        String subAccount = TokenService.getUsername();
        if (StringUtils.isEmpty(subAccount)) {
            return AjaxResult.error("Login session expired");
        }

        Map<String, Object> data = subTaskService.queryGroupedTasks(subAccount, taskId, pageNum, pageSize);
        return AjaxResult.success(data);
    }

    /**
     * 客户端：查询某个父任务ID下的所有子任务明细（抽屉展示）
     */
    @GetMapping("/detail")
    public AjaxResult detail(@RequestParam String taskId,
                             @RequestParam(defaultValue = "5000") int limit) {
        String subAccount = TokenService.getUsername();
        if (StringUtils.isEmpty(subAccount)) {
            return AjaxResult.error("Login session expired, please log in again");
        }
        if (StringUtils.isEmpty(taskId)) {
            return AjaxResult.error("Missing parent task ID");
        }

        try {
            List<Map<String, Object>> list =
                    subTaskService.querySubTasks(taskId, subAccount, limit);
            return AjaxResult.success(list);
        } catch (Exception e) {
            return AjaxResult.error("Query failed: " + e.getMessage());
        }
    }

    /**
     * 客户端：操作自己的子任务（暂停/恢复/取消）
     */
    @PostMapping("/operate")
    public AjaxResult operate(@RequestParam String action,
                              @RequestParam String taskId,
                              @RequestParam(defaultValue = "0") int subIndex) {
        String subAccount = TokenService.getUsername();
        if (StringUtils.isEmpty(subAccount)) {
            return AjaxResult.error("Login session expired, please log in again");
        }

        // 1. 安全校验：这个 taskId 是否属于当前用户
        List<Map<String, Object>> check = subTaskService.querySubTasks(taskId, subAccount, 1);
        if (check == null || check.isEmpty()) {
            return AjaxResult.error("No permission to operate this task, or the task does not exist");
        }

        try {
            int affected = subTaskService.operateSubTask(action, taskId, subIndex, "Client operation by: " + subAccount);
            if (affected > 0) {
                return AjaxResult.success("Operation succeeded, " + affected + " subtask(s) affected");
            } else {
                return AjaxResult.error("Operation did not take effect, the task may not be in the required status");
            }
        } catch (Exception e) {
            return AjaxResult.error("Operation failed: " + e.getMessage());
        }
    }
}