package com.texcellsms.sms.controller;

import com.texcellsms.sms.model.entity.ChargeLog;
import com.texcellsms.sms.service.ChargeLogService;
import com.texcellsms.sms.util.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;


import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


import javax.annotation.Resource;
import java.util.List;

@Tag(name = "充值记录接口", description = "查询当前登录用户的充值记录")
@RestController
@RequestMapping("/api/prepay/charge")
public class ChargeLogController {

    @Resource
    private ChargeLogService chargeLogService;

    /**
     * 方式1：自动获取当前登录用户的Account（推荐，无需前端传参）
     * （需确保Spring Security中存储的用户身份是Account）
     */
    @Operation(summary = "查询当前用户充值记录", description = "自动获取当前登录用户的Account，返回处理后的充值记录（余额/备注已转换）")
    @GetMapping("/my-log")
    public AjaxResult getMyChargeLog(
            @Parameter(description = "开始时间", example = "2026-03-10 00:00:00")
            @RequestParam(required = false) String startTime,
            @Parameter(description = "结束时间", example = "2026-03-13 23:59:59")
            @RequestParam(required = false) String endTime) {
        return AjaxResult.success(chargeLogService.getChargeLogByUserAccount(startTime, endTime));
    }
}