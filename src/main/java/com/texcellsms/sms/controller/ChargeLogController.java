package com.texcellsms.sms.controller;

import com.texcellsms.sms.model.entity.ChargeLog;
import com.texcellsms.sms.service.ChargeLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;


import org.springframework.security.core.context.SecurityContextHolder;
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
    public List<ChargeLog> getMyChargeLog() {
        // 获取当前登录用户的Account（核心修正：从Security上下文获取用户Account）
        String currentUserAccount = SecurityContextHolder.getContext().getAuthentication().getName();
        return chargeLogService.getChargeLogByUserAccount(currentUserAccount);
    }

    /**
     * 方式2：前端传参指定Account（用于测试/管理员查询）
     * （生产环境需加权限校验，仅管理员可查询其他用户）
     */
    @Operation(summary = "按Account查询充值记录(用于调试)", description = "传入用户Account，返回处理后的充值记录（余额/备注已转换）")
    @GetMapping("/by-account")
    public List<ChargeLog> getChargeLogByAccount(
            @Parameter(description = "充值用户Account（账号）", required = true)
            @RequestParam String account) {
        return chargeLogService.getChargeLogByUserAccount(account);
    }
}