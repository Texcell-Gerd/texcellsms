//package com.texcellsms.sms.controller;
//
//import com.beust.jcommander.Parameter;
//import com.texcellsms.sms.model.entity.ChargeLog;
//import io.swagger.v3.oas.annotations.Operation;
//import io.swagger.v3.oas.annotations.tags.Tag;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.RequestParam;
//import org.springframework.web.bind.annotation.RestController;
//
//import java.util.List;
//
//@Tag(name = "账单接口", description = "费用账单查询接口")
//@RestController
//public class SmsController {
//    @Operation(summary = "查询充值日志", description = "根据账户查询tbl_ChargeLog数据")
//    @GetMapping("/api/charge/query")
//    public List<ChargeLog> queryChargeLog(
////            @Parameter(description = "账户（手机号）", required = true)
//            @RequestParam String account) {
////        return chargeLogService.getChargeLogByAccount(account);
//        return null;
//    }
//}