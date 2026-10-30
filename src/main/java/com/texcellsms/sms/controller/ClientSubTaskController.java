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
     * 客户端：查询自己的子任务列表
     */
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) String taskId,
                           @RequestParam(defaultValue = "100") int limit) {
        String subAccount = TokenService.getUsername();
        if (StringUtils.isEmpty(subAccount)) {
            return AjaxResult.error("登录已过期，请重新登录");
        }

        // Service 里的参数顺序是 (taskId, subAccount, limit)
        List<Map<String, Object>> list = subTaskService.querySubTasks(taskId, subAccount, limit);
        return AjaxResult.success(list);
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
            return AjaxResult.error("登录已过期，请重新登录");
        }

        // 1. 安全校验：检查这个 taskId 是否属于当前用户
        // 使用 querySubTasks 来校验，参数为 (taskId, subAccount, 1) 只查1条
        List<Map<String, Object>> check = subTaskService.querySubTasks(taskId, subAccount, 1);
        if (check == null || check.isEmpty()) {
            return AjaxResult.error("无权限操作该任务，或任务不存在");
        }

        try {
            // 2. 执行操作，参数为 (action, taskId, subIndex, remark)
            int affected = subTaskService.operateSubTask(action, taskId, subIndex, "客户端操作: " + subAccount);
            if (affected > 0) {
                return AjaxResult.success("操作成功，影响了 " + affected + " 个子任务");
            } else {
                return AjaxResult.error("操作未生效，任务可能不处于该状态");
            }
        } catch (Exception e) {
            return AjaxResult.error("操作失败: " + e.getMessage());
        }
    }
}