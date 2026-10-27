package com.texcellsms.sms.controller;

import com.texcellsms.sms.service.SubTaskService;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/eccom/subtask")
public class ClientSubTaskController {

    @Autowired
    private SubTaskService subTaskService;

    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) String taskId,
                           @RequestParam(defaultValue = "100") int limit) {
        String subAccount = TokenService.getUsername();
        List<Map<String, String>> list = subTaskService.queryMySubTasks(subAccount, taskId, limit);
        return AjaxResult.success(list);
    }

    @PostMapping("/operate")
    public AjaxResult operate(@RequestParam String action,
                              @RequestParam String taskId,
                              @RequestParam(defaultValue = "0") int subIndex) {
        String subAccount = TokenService.getUsername();

        // 安全校验：检查这个 taskId 是否属于当前用户
        // 因为返回的是 Map，所以用 List<Map<String, String>>
        List<Map<String, String>> check = subTaskService.queryMySubTasks(subAccount, taskId, 1);
        if (check == null || check.isEmpty()) {
            return AjaxResult.error("无权限操作该任务");
        }

        // 执行操作
        int affected = subTaskService.operateSubTask(action, taskId, subIndex, "客户端操作: " + subAccount);
        return AjaxResult.success("操作成功，影响了 " + affected + " 个子任务");
    }
}