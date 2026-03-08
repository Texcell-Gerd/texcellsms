package com.texcellsms.sms.controller;

import com.texcellsms.sms.model.DTO.AnalyzeDTO;
import com.texcellsms.sms.model.DTO.SubmitRecordDTO;
import com.texcellsms.sms.service.AnalyzeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/api/prepay/analyze")
@Tag(name = "分析页", description = "分析页数据查询")
public class AnalyzeController {

    @Resource
    private AnalyzeService analyzeService;

    private static final SimpleDateFormat DATETIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @GetMapping("/query")
    @Operation(summary = "分析页查询", description = "查询分析页所需要数据")
    public AnalyzeDTO Query(
            @Parameter(description = "用户账号", example = "CTU506AT") @RequestParam String sourAccount,
            @Parameter(description = "开始时间（yyyy-MM-dd HH:mm:ss）", example = "2026-02-08 09:00:00") @RequestParam(required = false) String startTime,
            @Parameter(description = "结束时间（yyyy-MM-dd HH:mm:ss）", example = "2026-02-08 12:00:00") @RequestParam(required = false) String endTime,
            @Parameter(description = "MCC", example = "454") @RequestParam(required = false) String mcc,
            @Parameter(description = "MNC", example = "01") @RequestParam(required = false) String mnc,
            @Parameter(description = "页码", example = "1") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "页大小", example = "20") @RequestParam(defaultValue = "20") Integer pageSize
    ) throws ParseException {
        Date start = startTime != null ? DATETIME_FORMAT.parse(startTime) : null;
        Date end = endTime != null ? DATETIME_FORMAT.parse(endTime) : null;
        return analyzeService.queryAnalyzeData(sourAccount, start,end,mcc, mnc, pageNum, pageSize);
    }

}
