package com.texcellsms.sms.controller;

import com.texcellsms.sms.model.entity.ChargeLog;
import com.texcellsms.sms.service.EccomChargeLogService;
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

/**
 * eccom 充值记录接口（复用ChargeLog实体，接口前缀区分prepay）
 */
@Tag(name = "eccom充值记录接口", description = "查询eccom场景下当前登录用户的充值记录")
@RestController
@RequestMapping("/api/eccom/charge") // 核心：接口前缀加eccom，避免和prepay冲突
public class EccomChargeLogController {

    @Resource
    private EccomChargeLogService eccomChargeLogService;

    /**
     * 方式1：自动获取当前登录用户的Account（eccom专属）
     */
    @Operation(summary = "查询eccom当前用户充值记录", description = "自动获取当前登录用户的Account，返回eccom场景下处理后的充值记录")
    @GetMapping("/my-log")
    public List<ChargeLog> getMyChargeLog() {
        String currentUserAccount = SecurityContextHolder.getContext().getAuthentication().getName();
        return eccomChargeLogService.getChargeLogByUserAccount(currentUserAccount);
    }

    /**
     * 方式2：前端传参指定Account（eccom调试用）
     */
    @Operation(summary = "eccom按Account查询充值记录(用于调试)", description = "传入用户Account，返回eccom场景下处理后的充值记录")
    @GetMapping("/by-account")
    public List<ChargeLog> getChargeLogByAccount(
            @Parameter(description = "eccom充值用户Account（账号）", required = true)
            @RequestParam String account) {
        return eccomChargeLogService.getChargeLogByUserAccount(account);
    }
}