package com.texcellsms.sms.controller;

import com.texcellsms.sms.model.DTO.SubmitRecordDTO;
import com.texcellsms.sms.model.DTO.SubmitRecordWithStatsDTO;
import com.texcellsms.sms.service.EccomSubmitRecordService;
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
@RequestMapping("/api/eccom/submit-record")
@Tag(name = "Eccom短信发送记录查询", description = "Eccom数据源 - Submit_Bill主表+Fee_Bill副表关联查询")
public class EccomSubmitRecordController {

    @Resource
    private EccomSubmitRecordService eccomSubmitRecordService;

    private static final SimpleDateFormat DATETIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @GetMapping("/debug/query")
    @Operation(summary = "Eccom调试查询（指定用户）", description = "根据用户账号查询发送记录")
    public List<SubmitRecordDTO> queryDebug(
            @Parameter(description = "用户账号", example = "CTU506AT") @RequestParam String sourAccount,
            @Parameter(description = "开始时间（yyyy-MM-dd HH:mm:ss）", example = "2026-02-08 09:00:00") @RequestParam(required = false) String startTime,
            @Parameter(description = "结束时间（yyyy-MM-dd HH:mm:ss）", example = "2026-02-08 12:00:00") @RequestParam(required = false) String endTime,
            @Parameter(description = "被叫号码", example = "85212345678") @RequestParam(required = false) String callee,
            @Parameter(description = "MCC", example = "454") @RequestParam(required = false) String mcc,
            @Parameter(description = "MNC", example = "01") @RequestParam(required = false) String mnc,
            @Parameter(description = "回执状态", example = "DELIVRD") @RequestParam(required = false) String innerReportState,
            @Parameter(description = "回执状态码", example = "088") @RequestParam(required = false) String innerReportError,
            @Parameter(description = "页码", example = "1") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "页大小", example = "20") @RequestParam(defaultValue = "20") Integer pageSize
    ) throws ParseException {
        Date start = startTime != null ? DATETIME_FORMAT.parse(startTime) : null;
        Date end = endTime != null ? DATETIME_FORMAT.parse(endTime) : null;
        return eccomSubmitRecordService.getSubmitRecordsForDebug(
                sourAccount, start, end,
                callee, mcc, mnc,
                innerReportState, innerReportError,
                pageNum, pageSize
        );
    }

    @GetMapping("/login-user/query")
    @Operation(summary = "Eccom线上查询（当前登录用户）", description = "根据当前登录账号查询发送记录")
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
        return eccomSubmitRecordService.getSubmitRecordsByLoginUser(
                loginUserAccount, start, end,
                callee, mcc, mnc,
                innerReportState, innerReportError,
                pageNum, pageSize
        );
    }

    @GetMapping("/export/batch-excel")
    @Operation(summary = "Eccom批量导出Excel（支持30万条）", description = "分批查询+流式写入，避免内存溢出")
    public void batchExportExcel(
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
        Date start = DATETIME_FORMAT.parse(startTime);
        Date end = DATETIME_FORMAT.parse(endTime);

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String fileName = URLEncoder.encode("Eccom短信发送记录_批量导出" + System.currentTimeMillis() + ".xlsx", "UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=" + fileName);
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Cache-Control", "no-cache");
        response.setBufferSize(1024 * 1024);

        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        Sheet sheet = workbook.createSheet("Eccom短信发送记录");
        OutputStream outputStream = response.getOutputStream();

        try {
            Row headerRow = sheet.createRow(0);
            String[] headers = {
                    "用户账号", "提交消息ID", "提交时间", "主叫", "被叫",
                    "短信长度", "实际费用", "短信内容", "MCC", "MNC",
                    "回执状态", "回执时间"
            };
            int[] columnWidths = {12, 15, 20, 10, 15, 8, 10, 50, 6, 6, 10, 20};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                sheet.setColumnWidth(i, columnWidths[i] * 256);
            }

            int pageNum = 1;
            int totalCount = 0;
            final int BATCH_SIZE = 1000;
            while (true) {
                List<SubmitRecordDTO> batchRecords = eccomSubmitRecordService.getSubmitRecordsForDebug(
                        sourAccount, start, end,
                        callee, mcc, mnc,
                        innerReportState, innerReportError,
                        pageNum, BATCH_SIZE
                );

                if (batchRecords.isEmpty()) {
                    break;
                }

                for (int i = 0; i < batchRecords.size(); i++) {
                    SubmitRecordDTO record = batchRecords.get(i);
                    Row dataRow = sheet.createRow(totalCount + 1);
                    dataRow.createCell(0).setCellValue(record.getSourAccount() == null ? "" : record.getSourAccount());
                    dataRow.createCell(1).setCellValue(record.getSubmitMsgId() == null ? "" : record.getSubmitMsgId());
                    dataRow.createCell(2).setCellValue(record.getSubmitMsgTime() == null ? "" : record.getSubmitMsgTime());
                    dataRow.createCell(3).setCellValue(record.getCaller() == null ? "" : record.getCaller());
                    dataRow.createCell(4).setCellValue(record.getCallee() == null ? "" : record.getCallee());
                    dataRow.createCell(5).setCellValue(record.getSmLength() == null ? "" : record.getSmLength());
                    dataRow.createCell(6).setCellValue(record.getActualChargeFee() == null ? "" : record.getActualChargeFee());
                    dataRow.createCell(7).setCellValue(record.getShortMessage() == null ? "" : record.getShortMessage());
                    dataRow.createCell(8).setCellValue(record.getMcc() == null ? "" : record.getMcc());
                    dataRow.createCell(9).setCellValue(record.getMnc() == null ? "" : record.getMnc());
                    dataRow.createCell(10).setCellValue(record.getInnerReportState() == null ? "" : record.getInnerReportState());
                    dataRow.createCell(11).setCellValue(record.getRecvDRTime() == null ? "" : record.getRecvDRTime());

                    totalCount++;
                }

                ((SXSSFSheet) sheet).flushRows();
                outputStream.flush();

                pageNum++;
            }

            workbook.write(outputStream);
            outputStream.flush();
        } finally {
            workbook.dispose();
            outputStream.close();
        }
    }

    @GetMapping("/with-stats/debug")
    @Operation(summary = "Eccom调试查询（带统计）", description = "指定用户+返回发送记录+提交/送达统计信息")
    public SubmitRecordWithStatsDTO queryDebugWithStats(
            @Parameter(description = "用户账号", example = "CTU506AT") @RequestParam String sourAccount,
            @Parameter(description = "开始时间（yyyy-MM-dd HH:mm:ss）", example = "2026-02-08 09:00:00") @RequestParam(required = false) String startTime,
            @Parameter(description = "结束时间（yyyy-MM-dd HH:mm:ss）", example = "2026-02-08 12:00:00") @RequestParam(required = false) String endTime,
            @Parameter(description = "被叫号码", example = "85212345678") @RequestParam(required = false) String callee,
            @Parameter(description = "MCC", example = "454") @RequestParam(required = false) String mcc,
            @Parameter(description = "MNC", example = "01") @RequestParam(required = false) String mnc,
            @Parameter(description = "回执状态", example = "DELIVRD") @RequestParam(required = false) String innerReportState,
            @Parameter(description = "回执状态码", example = "088") @RequestParam(required = false) String innerReportError,
            @Parameter(description = "页码", example = "1") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "页大小", example = "20") @RequestParam(defaultValue = "20") Integer pageSize
    ) throws ParseException {
        Date start = startTime != null ? DATETIME_FORMAT.parse(startTime) : null;
        Date end = endTime != null ? DATETIME_FORMAT.parse(endTime) : null;
        return eccomSubmitRecordService.getSubmitRecordWithStats(
                sourAccount, start, end,
                callee, mcc, mnc,
                innerReportState, innerReportError,
                pageNum, pageSize
        );
    }

    @GetMapping("/with-stats/login-user")
    @Operation(summary = "Eccom线上查询（带统计）", description = "当前登录用户+返回发送记录+提交/送达统计信息")
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
        return eccomSubmitRecordService.getSubmitRecordWithStats(
                loginUserAccount, start, end,
                callee, mcc, mnc,
                innerReportState, innerReportError,
                pageNum, pageSize
        );
    }
}