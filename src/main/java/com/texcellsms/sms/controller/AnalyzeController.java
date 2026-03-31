package com.texcellsms.sms.controller;

import com.texcellsms.sms.constant.RequestHeaderConstants;
import com.texcellsms.sms.model.DTO.AnalyzeDTO;
import com.texcellsms.sms.model.DTO.DailyStatDTO;
import com.texcellsms.sms.service.AnalyzeService;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.Constants;
import com.texcellsms.sms.util.Result;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/eccom/analyze")
public class AnalyzeController {

    @Resource
    private AnalyzeService analyzeService;
    private final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /**
     * 分页查询数据分析列表（按时间、MCC、MNC筛选）
     */
    @GetMapping("/analysis-list")
    public Result<Map<String, Object>> query(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String mcc,
            @RequestParam(required = false) String mnc,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            HttpServletResponse response) throws ParseException {

        String newToken = TokenService.refreshToken(authorization);
        response.setHeader(Constants.HEADER, newToken);

        Date start = null;
        Date end = null;
        if (startTime != null) start = format.parse(startTime);
        if (endTime != null) end = format.parse(endTime);

        List<AnalyzeDTO> list = analyzeService.query(start, end, mcc, mnc, pageNum, pageSize);
        int total = analyzeService.total(start, end, mcc, mnc);

        Map<String, Object> data = new HashMap<>();
        data.put("data", list);
        data.put("total", total);
        data.put("pageNum", pageNum);
        data.put("pageSize", pageSize);

        return Result.success(data);
    }

    /**
     * 获取每日统计数据，用于图表展示
     */
    @GetMapping("/dailyStat")
    public Result<List<DailyStatDTO>> dailyStat(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            @RequestParam(required = false, defaultValue = "week") String period,
            @RequestParam(required = false) String mcc,
            HttpServletResponse response) {

        String newToken = TokenService.refreshToken(authorization);
        response.setHeader(Constants.HEADER, newToken);

        List<DailyStatDTO> data = analyzeService.getDailyStat(period, mcc);
        return Result.success(data);
    }
}