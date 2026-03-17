package com.texcellsms.sms.controller;

import com.texcellsms.sms.constant.RequestHeaderConstants;
import com.texcellsms.sms.model.entity.ChargeLog;
import com.texcellsms.sms.service.ChargeLogService;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.Constants;
import com.texcellsms.sms.util.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "充值记录接口")
@RestController
@RequestMapping("/api/eccom/charge")
public class ChargeLogController {

    @Resource
    private ChargeLogService chargeLogService;

    @Operation(summary = "查询当前用户充值记录（带分页）")
    @GetMapping("/recharge-log")
    public Result<Map<String, Object>> getMyChargeLog(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            @Parameter(description = "开始时间") @RequestParam(required = false) String startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) String endTime,
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
}