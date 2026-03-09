package com.texcellsms.sms.controller;


import com.texcellsms.sms.service.SmsQueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Tag(name = "短信队列任务管理")
@RestController
@RequestMapping("/api/sms/queue")
@RequiredArgsConstructor
public class SmsQueueController {

    private final SmsQueueService smsQueueService;

    @Operation(summary = "获取队列任务列表（分页）")
    @GetMapping("/tasks")
    public Map<String, Object> getQueueTasks(
            @Parameter(description = "页码，默认1") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页条数，默认10") @RequestParam(defaultValue = "10") Integer pageSize) {
        return smsQueueService.getQueueTasks(pageNum, pageSize);
    }

    @Operation(summary = "获取队列任务详情（分页）")
    @GetMapping("/task/detail")
    public Map<String, Object> getQueueTaskDetail(
            @Parameter(description = "任务ID", required = true) @RequestParam String taskId,
            @Parameter(description = "页码，默认1") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页条数，默认10") @RequestParam(defaultValue = "10") Integer pageSize,
            @Parameter(description = "接收号码") @RequestParam(required = false) String callee) {
        return smsQueueService.getQueueTaskDetail(taskId, pageNum, pageSize, callee);
    }

    @Operation(summary = "导出队列任务详情")
    @GetMapping("/task/export")
    public void exportQueueTaskDetail(
            @Parameter(description = "任务ID", required = true) @RequestParam String taskId,
            HttpServletResponse response) {
        try {
            smsQueueService.exportAllTaskDetailsByTaskId(taskId, response);
        } catch (Exception e) {
            response.setContentType("application/json;charset=UTF-8");
            try {
                response.getWriter().write("{\"code\":500,\"msg\":\"导出失败：" + e.getMessage() + "\"}");
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    // ========== 修改预约发送状态 ==========
    @Operation(summary = "批量删除任务（伪删除，改发送状态为4，用于预约发送的任务删除）")
    @PostMapping("/tasks/batch-delete")
    public Map<String, Object> batchDeleteTask(@RequestBody Set<String> taskIds) {

        Map<String, Object> map = new HashMap<>();

        if (taskIds == null || taskIds.isEmpty()) {
            map.put("code", 400);
            map.put("msg", "任务ID不能为空");
            return map;
        }
        int count = smsQueueService.batchDeleteTask(taskIds);

        map.put("code", 200);
        map.put("msg", "操作成功");
        map.put("total", taskIds.size());
        map.put("success", count);

        return map;
    }
}