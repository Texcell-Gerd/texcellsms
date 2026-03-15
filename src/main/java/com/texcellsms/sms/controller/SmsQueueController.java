package com.texcellsms.sms.controller;

import com.texcellsms.sms.constant.RequestHeaderConstants;
import com.texcellsms.sms.service.SmsQueueService;
import com.texcellsms.sms.util.Constants;
import com.texcellsms.sms.util.Result;
import com.texcellsms.sms.service.TokenService;
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
    public Result<Map<String, Object>> getQueueTasks(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            @Parameter(description = "页码，默认1") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页条数，默认10") @RequestParam(defaultValue = "10") Integer pageSize,
            HttpServletResponse response) {
        String newToken = TokenService.refreshToken(authorization);
        response.setHeader(Constants.HEADER, newToken);
        return Result.success(smsQueueService.getQueueTasks(pageNum, pageSize));
    }

    @Operation(summary = "获取队列任务详情（分页）")
    @GetMapping("/task/detail")
    public Result<Map<String, Object>> getQueueTaskDetail(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            @Parameter(description = "任务ID", required = true) @RequestParam String taskId,
            @Parameter(description = "页码，默认1") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页条数，默认10") @RequestParam(defaultValue = "10") Integer pageSize,
            @Parameter(description = "接收号码") @RequestParam(required = false) String callee,
            HttpServletResponse response) {
        String newToken = TokenService.refreshToken(authorization);
        response.setHeader(Constants.HEADER, newToken);
        return Result.success(smsQueueService.getQueueTaskDetail(taskId, pageNum, pageSize, callee));
    }

    @Operation(summary = "导出队列任务详情")
    @GetMapping("/task/export")
    public void exportQueueTaskDetail(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            @Parameter(description = "任务ID", required = true) @RequestParam String taskId,
            HttpServletResponse response) {
        String newToken = TokenService.refreshToken(authorization);
        response.setHeader(Constants.HEADER, newToken);
        try {
            smsQueueService.exportAllTaskDetailsByTaskId(taskId, response);
        } catch (Exception e) {
            try {
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":500,\"msg\":\"导出失败：" + e.getMessage() + "\"}");
            } catch (Exception ex) {}
        }
    }

    @Operation(summary = "批量删除任务")
    @PostMapping("/tasks/batch-delete")
    public Result<Map<String, Object>> batchDeleteTask(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            @RequestBody Set<String> taskIds,
            HttpServletResponse response) {
        String newToken = TokenService.refreshToken(authorization);
        response.setHeader(Constants.HEADER, newToken);

        if (taskIds == null || taskIds.isEmpty()) {
            return Result.fail(400, "任务ID不能为空");
        }
        int count = smsQueueService.batchDeleteTask(taskIds);
        Map<String, Object> map = new HashMap<>();
        map.put("total", taskIds.size());
        map.put("success", count);
        return Result.success(map);
    }
}