package com.texcellsms.sms.controller;

import com.texcellsms.sms.constant.RequestHeaderConstants;
import com.texcellsms.sms.model.DTO.AnalyzeDTO;
import com.texcellsms.sms.service.AnalyzeService;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.Constants;
import com.texcellsms.sms.util.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

@RestController
@RequestMapping("/api/prepay/analyze")
@Tag(name = "分析页")
public class AnalyzeController {

    @Resource
    private AnalyzeService analyzeService;
    private static final SimpleDateFormat DATETIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @GetMapping("/query")
    @Operation(summary = "分析页查询")
    public Result<AnalyzeDTO> Query(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            @Parameter(description = "用户账号") @RequestParam String sourAccount,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String mcc,
            @RequestParam(required = false) String mnc,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            HttpServletResponse response) throws ParseException {

        String newToken = TokenService.refreshToken(authorization);
        response.setHeader(Constants.HEADER, newToken);

        Date start = startTime != null ? DATETIME_FORMAT.parse(startTime) : null;
        Date end = endTime != null ? DATETIME_FORMAT.parse(endTime) : null;
        return Result.success(analyzeService.queryAnalyzeData(sourAccount, start, end, mcc, mnc, pageNum, pageSize));
    }
}