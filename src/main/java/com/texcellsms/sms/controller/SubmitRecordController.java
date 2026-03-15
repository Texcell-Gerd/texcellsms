package com.texcellsms.sms.controller;

import com.alibaba.excel.EasyExcel;
import com.texcellsms.sms.constant.RequestHeaderConstants;
import com.texcellsms.sms.model.DTO.SubmitRecordWithStatsDTO;
import com.texcellsms.sms.model.vo.SubmitRecordExportVO;
import com.texcellsms.sms.service.SubmitRecordService;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.Constants;
import com.texcellsms.sms.util.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/api/prepay/submit-record")
@Tag(name = "短信发送记录查询")
public class SubmitRecordController {

    @Resource
    private SubmitRecordService submitRecordService;
    private static final SimpleDateFormat DATETIME_FORMAT = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

    private static final Logger logger = LoggerFactory.getLogger(SubmitRecordController.class);

    @GetMapping("/with-stats/login-user")
    @Operation(summary = "发送记录查询（带统计）")
    public Result<SubmitRecordWithStatsDTO> queryLoginUserWithStats(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String callee,
            @RequestParam(required = false) String mcc,
            @RequestParam(required = false) String mnc,
            @RequestParam(required = false) String innerReportState,
            @RequestParam(required = false) String innerReportError,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            HttpServletResponse response) throws ParseException {

        String newToken = TokenService.refreshToken(authorization);
        response.setHeader(Constants.HEADER, newToken);

        Date start = startTime != null ? DATETIME_FORMAT.parse(startTime) : null;
        Date end = endTime != null ? DATETIME_FORMAT.parse(endTime) : null;
        return Result.success(submitRecordService.getSubmitRecordWithStats(
                start, end, callee, mcc, mnc, innerReportState, innerReportError, pageNum, pageSize
        ));
    }

    @GetMapping("/export/easyexcel")
    @Operation(summary = "EasyExcel批量导出")
    public void easyExcelExport(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            @RequestParam String startTime,
            @RequestParam String endTime,
            @RequestParam(required = false) String callee,
            @RequestParam(required = false) String mcc,
            @RequestParam(required = false) String mnc,
            @RequestParam(required = false) String innerReportState,
            @RequestParam(required = false) String innerReportError,
            HttpServletResponse response) {

        // 刷新token
        String newToken = TokenService.refreshToken(authorization);
        response.setHeader(Constants.HEADER, newToken);

        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
            Date start = format.parse(startTime);
            Date end = format.parse(endTime);

            List<SubmitRecordExportVO> data = submitRecordService.queryExportDataForEasyExcel(
                    start, end, callee, mcc, mnc, innerReportState, innerReportError
            );

            // 导出设置的参数
            String fileName = URLEncoder.encode("短信发送记录", "UTF-8").replace("+", "%20");
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Disposition", "attachment;filename*=UTF-8''" + fileName + ".xlsx");

            EasyExcel.write(response.getOutputStream(), SubmitRecordExportVO.class)
                    .sheet("短信发送记录")
                    .doWrite(data);

        } catch (Exception e) {
            try {
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":500,\"msg\":\"导出失败\"}");
            } catch (Exception ex) {}
        }
    }
}