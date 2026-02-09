package com.texcellsms.sms.controller;

import com.texcellsms.sms.model.DTO.SmsQueueDTO;
import com.texcellsms.sms.model.DTO.SmsQueueDetailDTO;
import com.texcellsms.sms.model.DTO.SmsQueueDetailItemDTO;
import com.texcellsms.sms.service.SmsQueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import java.util.List;

@RestController
@RequestMapping("/api/prepay/sms-queue")
@Tag(name = "短信队列接口", description = "按秒分组查询队列数据（列表+详情+导出）")
public class SmsQueueController {

    @Resource
    private SmsQueueService smsQueueService;

    /**
     * 线上查询队列列表（近三天）
     */
    @GetMapping("/list")
    @Operation(summary = "线上查询队列列表（近三天）", description = "当前登录用户+分页查询")
    public SmsQueueDTO getQueueList(
            @Parameter(description = "当前登录用户账号", example = "CTU506AT") @RequestParam String loginUserAccount,
            @Parameter(description = "当前页码", example = "1") @RequestParam(defaultValue = "1") Integer page,
            @Parameter(description = "每页条数", example = "10") @RequestParam(defaultValue = "10") Integer pageSize
    ) {
        return smsQueueService.getSmsQueueList(loginUserAccount, page, pageSize);
    }

    /**
     * 查询队列明细
     */
    @GetMapping("/detail")
    @Operation(summary = "查询队列明细", description = "指定秒级时间查询明细，支持接收号码精准匹配")
    public SmsQueueDetailDTO getQueueDetail(
            @Parameter(description = "当前登录用户账号", example = "CTU506AT") @RequestParam String loginUserAccount,
            @Parameter(description = "队列时间（yyyy/MM/dd HH:mm:ss）", example = "2026/02/09 13:04:55") @RequestParam String targetTime,
            @Parameter(description = "接收号码（精准匹配，可选）", example = "5562994549550") @RequestParam(required = false) String callee
    ) {
        return smsQueueService.getSmsQueueDetail(loginUserAccount, targetTime, callee);
    }

    /**
     * 导出单个队列详情到Excel
     */
    @GetMapping("/detail/export-excel")
    @Operation(summary = "导出单个队列详情到Excel", description = "导出指定秒级队列的明细数据，支持接收号码精准匹配")
    public void exportQueueDetailExcel(
            @Parameter(description = "当前登录用户账号", example = "CTU506AT") @RequestParam String loginUserAccount,
            @Parameter(description = "队列时间（yyyy/MM/dd HH:mm:ss）", example = "2026/02/09 13:04:55") @RequestParam String targetTime,
            @Parameter(description = "接收号码（精准匹配，可选）", example = "5562994549550") @RequestParam(required = false) String callee,
            HttpServletResponse response
    ) throws Exception {
        // 逻辑不变，仅参数描述更新
        SmsQueueDetailDTO detailDTO = smsQueueService.getSmsQueueDetail(loginUserAccount, targetTime, callee);
        List<SmsQueueDetailItemDTO> details = detailDTO.getDetails();
        if (details.isEmpty()) {
            throw new RuntimeException("该队列为空，无数据可导出");
        }

        // 2. 设置响应头
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String fileName = URLEncoder.encode("队列详情_" + targetTime.replaceAll("/|:", "-") + ".xlsx", "UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=" + fileName);
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Cache-Control", "no-cache");

        // 3. 创建流式Excel
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        SXSSFSheet sheet = (SXSSFSheet) workbook.createSheet("队列详情");
        OutputStream outputStream = response.getOutputStream();

        try {
            // 4. 写入表头
            org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(0);
            String[] headers = {"编号", "发件人", "接收号码", "完成时间", "发送结果", "原因说明", "计费数量", "拆分索引"};
            int[] columnWidths = {12, 10, 15, 20, 10, 15, 10, 10};
            for (int i = 0; i < headers.length; i++) {
                org.apache.poi.ss.usermodel.Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                sheet.setColumnWidth(i, columnWidths[i] * 256);
            }

            // 5. 写入明细数据
            for (int i = 0; i < details.size(); i++) {
                SmsQueueDetailItemDTO item = details.get(i);
                org.apache.poi.ss.usermodel.Row dataRow = sheet.createRow(i + 1);
                // 填充数据
                dataRow.createCell(0).setCellValue(item.getSubmitMsgId() == null ? "" : item.getSubmitMsgId());
                dataRow.createCell(1).setCellValue("SMSINFO"); // 固定发件人
                dataRow.createCell(2).setCellValue(item.getCallee() == null ? "" : item.getCallee());
                dataRow.createCell(3).setCellValue(item.getRecvDRTime() == null ? "" : item.getRecvDRTime());
                dataRow.createCell(4).setCellValue(item.getInnerReportState() == null ? "已提交" : item.getInnerReportState());
                dataRow.createCell(5).setCellValue(item.getInnerReportError() == null ? "" : item.getInnerReportError());
                dataRow.createCell(6).setCellValue(1); // 固定计费数量
                dataRow.createCell(7).setCellValue(1); // 固定拆分索引
            }

            // 6. 写入响应
            workbook.write(outputStream);
            outputStream.flush();
        } finally {
            // 7. 关闭资源
            workbook.dispose();
            outputStream.close();
        }
    }

    /**
     * 测试环境查询队列列表（指定时间范围）
     */
    @GetMapping("/test/list")
    @Operation(summary = "测试环境查询队列列表", description = "指定时间范围+分页")
    public SmsQueueDTO getQueueListForTest(
            @Parameter(description = "用户账号", example = "CTU506AT") @RequestParam String loginUserAccount,
            @Parameter(description = "开始时间（yyyy-MM-dd HH:mm:ss）", example = "2026-02-06 13:04:00") @RequestParam String startTime,
            @Parameter(description = "结束时间（yyyy-MM-dd HH:mm:ss）", example = "2026-02-09 13:04:00") @RequestParam String endTime,
            @Parameter(description = "页码", example = "1") @RequestParam(defaultValue = "1") Integer page,
            @Parameter(description = "页大小", example = "10") @RequestParam(defaultValue = "10") Integer pageSize
    ) {
        return smsQueueService.getSmsQueueListForTest(loginUserAccount, startTime, endTime, page, pageSize);
    }
}