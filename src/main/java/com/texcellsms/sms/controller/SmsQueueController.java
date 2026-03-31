package com.texcellsms.sms.controller;

import com.texcellsms.sms.constant.RequestHeaderConstants;
import com.texcellsms.sms.service.SmsQueueService;
import com.texcellsms.sms.util.Constants;
import com.texcellsms.sms.util.Result;
import com.texcellsms.sms.service.TokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/sms/queue")
@RequiredArgsConstructor
public class SmsQueueController {

    private final SmsQueueService smsQueueService;

    /**
     * 分页获取短信队列任务列表
     */
    @GetMapping("/tasks")
    public Result<Map<String, Object>> getQueueTasks(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            HttpServletResponse response) {
        String newToken = TokenService.refreshToken(authorization);
        response.setHeader(Constants.HEADER, newToken);
        return Result.success(smsQueueService.getQueueTasks(pageNum, pageSize));
    }

    /**
     * 查询单个队列任务的详细发送记录
     */
    @GetMapping("/task/detail")
    public Result<Map<String, Object>> getQueueTaskDetail(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            @RequestParam String taskId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String callee,
            HttpServletResponse response) {
        String newToken = TokenService.refreshToken(authorization);
        response.setHeader(Constants.HEADER, newToken);
        return Result.success(smsQueueService.getQueueTaskDetail(taskId, pageNum, pageSize, callee));
    }

    /**
     * 导出指定任务的全部明细记录
     */
    @GetMapping("/task/export")
    public void exportQueueTaskDetail(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            @RequestParam String taskId,
            HttpServletResponse response) {
        String newToken = TokenService.refreshToken(authorization);
        response.setHeader(Constants.HEADER, newToken);
        try {
            smsQueueService.exportAllTaskDetailsByTaskId(taskId, response);
        } catch (Exception e) {
            try {
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":500,\"msg\":\"Export failed: " + e.getMessage() + "\"}");
            } catch (Exception ex) {}
        }
    }

    /**
     * 批量删除短信队列任务
     */
    @PostMapping("/tasks/batch-delete")
    public Result<Map<String, Object>> batchDeleteTask(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            @RequestBody Set<String> taskIds,
            HttpServletResponse response) {
        String newToken = TokenService.refreshToken(authorization);
        response.setHeader(Constants.HEADER, newToken);

        if (taskIds == null || taskIds.isEmpty()) {
            return Result.fail(400, "Task ID cannot be empty");
        }
        int count = smsQueueService.batchDeleteTask(taskIds);
        Map<String, Object> map = new HashMap<>();
        map.put("total", taskIds.size());
        map.put("success", count);
        return Result.success(map);
    }
}