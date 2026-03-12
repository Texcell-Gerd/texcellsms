package com.texcellsms.sms.controller;

import com.alibaba.excel.EasyExcel;
import com.texcellsms.sms.model.DTO.SubmitRecordDTO;
import com.texcellsms.sms.model.DTO.SubmitRecordWithStatsDTO;
import com.texcellsms.sms.model.vo.SubmitRecordExportVO;
import com.texcellsms.sms.service.SubmitRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;



import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/api/prepay/submit-record") //
@Tag(name = "短信发送记录查询", description = "Submit_Bill主表+Fee_Bill副表关联查询")
public class SubmitRecordController {

    @Resource
    private SubmitRecordService submitRecordService;

    // 核心修复：支持横杠分隔的日期格式（yyyy-MM-dd HH:mm:ss）
    private static final SimpleDateFormat DATETIME_FORMAT = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

    @GetMapping("/login-user/query")
    @Operation(summary = "线上查询（当前登录用户）", description = "根据当前登录账号查询发送记录")
    public List<SubmitRecordDTO> queryLoginUser(
            @Parameter(description = "当前登录用户账号", example = "CTU506AT") @RequestParam String loginUserAccount,
            @Parameter(description = "开始时间（yyyy-MM-dd HH:mm:ss）", example = "2026-02-08 09:00:00") @RequestParam(required = false) String startTime,
            @Parameter(description = "结束时间（yyyy-MM-dd HH:mm:ss）", example = "2026-02-08 12:00:00") @RequestParam(required = false) String endTime,
            @Parameter(description = "被叫号码", example = "85212345678") @RequestParam(required = false) String callee,
            @Parameter(description = "MCC", example = "454") @RequestParam(required = false) String mcc,
            @Parameter(description = "MNC", example = "01") @RequestParam(required = false) String mnc,
            @Parameter(description = "回执状态", example = "DELIVRD") @RequestParam(required = false) String innerReportState,
            @Parameter(description = "回执状态码", example = "000") @RequestParam(required = false) String innerReportError,
            @Parameter(description = "页码", example = "1") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "页大小", example = "20") @RequestParam(defaultValue = "20") Integer pageSize
    ) throws ParseException {
        Date start = startTime != null ? DATETIME_FORMAT.parse(startTime) : null;
        Date end = endTime != null ? DATETIME_FORMAT.parse(endTime) : null;
        return submitRecordService.getSubmitRecordsByLoginUser(
                start, end,
                callee, mcc, mnc,
                innerReportState, innerReportError,
                pageNum, pageSize
        );
    }

    // ========== 新增：统计查询接口 ==========
    @GetMapping("/with-stats/login-user")
    @Operation(summary = "线上查询（带统计）", description = "当前登录用户+返回发送记录+提交/送达统计信息")
    public SubmitRecordWithStatsDTO queryLoginUserWithStats(
            @Parameter(description = "当前登录用户账号", example = "CTU506AT") @RequestParam String loginUserAccount,
            @Parameter(description = "开始时间（yyyy-MM-dd HH:mm:ss）", example = "2026-02-08 09:00:00") @RequestParam(required = false) String startTime,
            @Parameter(description = "结束时间（yyyy-MM-dd HH:mm:ss）", example = "2026-02-08 12:00:00") @RequestParam(required = false) String endTime,
            @Parameter(description = "被叫号码", example = "85212345678") @RequestParam(required = false) String callee,
            @Parameter(description = "MCC", example = "454") @RequestParam(required = false) String mcc,
            @Parameter(description = "MNC", example = "01") @RequestParam(required = false) String mnc,
            @Parameter(description = "回执状态", example = "DELIVRD") @RequestParam(required = false) String innerReportState,
            @Parameter(description = "回执状态码", example = "000") @RequestParam(required = false) String innerReportError,
            @Parameter(description = "页码", example = "1") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "页大小", example = "20") @RequestParam(defaultValue = "20") Integer pageSize
    ) throws ParseException {
        Date start = startTime != null ? DATETIME_FORMAT.parse(startTime) : null;
        Date end = endTime != null ? DATETIME_FORMAT.parse(endTime) : null;
        return submitRecordService.getSubmitRecordWithStats(
                start, end,
                callee, mcc, mnc,
                innerReportState, innerReportError,
                pageNum, pageSize
        );
    }

    // ========== EasyExcel 导出接口 ==========
    @GetMapping("/export/easyexcel")
    @Operation(summary = "EasyExcel批量导出（支持百万级数据）", description = "流式导出")
    public void easyExcelExport(
            @Parameter(description = "用户账号", example = "CTU506AT") @RequestParam String sourAccount,
            @Parameter(description = "开始时间（yyyy-MM-dd HH:mm:ss）", example = "2026-02-08 00:00:00") @RequestParam String startTime,
            @Parameter(description = "结束时间（yyyy-MM-dd HH:mm:ss）", example = "2026-02-08 23:59:59") @RequestParam String endTime,
            @Parameter(description = "被叫号码", example = "85212345678") @RequestParam(required = false) String callee,
            @Parameter(description = "MCC", example = "454") @RequestParam(required = false) String mcc,
            @Parameter(description = "MNC", example = "01") @RequestParam(required = false) String mnc,
            @Parameter(description = "回执状态", example = "DELIVRD") @RequestParam(required = false) String innerReportState,
            @Parameter(description = "回执状态码", example = "000") @RequestParam(required = false) String innerReportError,
            HttpServletResponse response
    ) throws Exception {
        // 1. 解析时间参数
        Date start = DATETIME_FORMAT.parse(startTime);
        Date end = DATETIME_FORMAT.parse(endTime);

        // 2. 设置响应头（优化中文文件名）
        String fileName = URLEncoder.encode("短信发送记录_" + System.currentTimeMillis(), "UTF-8");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + ".xlsx\"");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Cache-Control", "no-cache");

        // 3. 查询导出数据（EasyExcel 自动流式处理）
        List<SubmitRecordExportVO> exportData = submitRecordService.queryExportDataForEasyExcel(
                start, end, callee, mcc, mnc, innerReportState, innerReportError
        );

        // 4. EasyExcel 核心导出逻辑
        EasyExcel.write(response.getOutputStream(), SubmitRecordExportVO.class)
                .sheet("短信发送记录")
                .doWrite(exportData);
    }
}