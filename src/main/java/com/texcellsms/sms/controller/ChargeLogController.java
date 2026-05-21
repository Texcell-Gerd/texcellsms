package com.texcellsms.sms.controller;

import com.texcellsms.sms.constant.RequestHeaderConstants;
import com.texcellsms.sms.model.entity.ChargeLog;
import com.texcellsms.sms.service.ChargeLogService;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.Constants;
import com.texcellsms.sms.util.Result;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/eccom/charge")
public class ChargeLogController {

    @Resource
    private ChargeLogService chargeLogService;

    /**
     * 分页查询当前用户的充值记录
     */
    @GetMapping("/recharge-log")
    public Result<Map<String, Object>> getMyChargeLog(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            HttpServletResponse response) {

        String newToken = TokenService.refreshToken(authorization);
        response.setHeader(Constants.HEADER, newToken);

        List<ChargeLog> list = chargeLogService.getChargeLogPage(startTime, endTime, pageNum, pageSize);
        int total = chargeLogService.getChargeLogCount(startTime, endTime);

        Map<String, Object> result = new HashMap<>();
        result.put("records", list);
        result.put("total", total);

        return Result.success(result);
    }

    /**
     * 获取充值记录统计数据
     */
    @GetMapping("/recharge-log/statistics")
    public Result<Map<String, Object>> getChargeLogStatistics(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            HttpServletResponse response) {

        String newToken = TokenService.refreshToken(authorization);
        response.setHeader(Constants.HEADER, newToken);

        List<ChargeLog> allLogs = chargeLogService.getChargeLogByUserAccount(startTime, endTime);

        // 使用 BigDecimal 进行精确计算
        java.math.BigDecimal totalAmount = java.math.BigDecimal.ZERO;
        java.math.BigDecimal maxAmount = java.math.BigDecimal.ZERO;

        for (ChargeLog log : allLogs) {
            java.math.BigDecimal amount = log.getAmount() != null ? log.getAmount() : java.math.BigDecimal.ZERO;
            totalAmount = totalAmount.add(amount);
            if (amount.compareTo(maxAmount) > 0) {
                maxAmount = amount;
            }
        }

        // 计算平均值
        java.math.BigDecimal avgAmount = java.math.BigDecimal.ZERO;
        if (!allLogs.isEmpty()) {
            avgAmount = totalAmount.divide(java.math.BigDecimal.valueOf(allLogs.size()), 4, java.math.RoundingMode.HALF_UP);
        }

        // 转换为 double 返回（或保持 BigDecimal）
        Map<String, Object> result = new HashMap<>();
        result.put("totalAmount", totalAmount.doubleValue());
        result.put("avgAmount", avgAmount.doubleValue());
        result.put("maxAmount", maxAmount.doubleValue());
        result.put("totalCount", allLogs.size());

        return Result.success(result);
    }
}