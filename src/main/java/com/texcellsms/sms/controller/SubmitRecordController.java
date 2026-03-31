package com.texcellsms.sms.controller;

import com.alibaba.excel.EasyExcel;
import com.texcellsms.sms.constant.RequestHeaderConstants;
import com.texcellsms.sms.model.DTO.SubmitRecordWithStatsDTO;
import com.texcellsms.sms.model.vo.SubmitRecordExportVO;
import com.texcellsms.sms.service.SubmitRecordService;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.Constants;
import com.texcellsms.sms.util.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/api/eccom/submit-record")
public class SubmitRecordController {

    @Resource
    private SubmitRecordService submitRecordService;
    private static final SimpleDateFormat DATETIME_FORMAT = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

    private static final Logger logger = LoggerFactory.getLogger(SubmitRecordController.class);

    /**
     * 分页查询短信发送记录并返回统计信息
     */
    @GetMapping("/Send-records")
    public Result<SubmitRecordWithStatsDTO> queryLoginUserWithStats(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String callee,
            @RequestParam(required = false) String mcc,
            @RequestParam(required = false) String mnc,
            @RequestParam(required = false) String innerReportState,
            @RequestParam(required = false) String innerReportError,
            @RequestParam(required = false) String destAccountStatus,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            HttpServletResponse response) throws ParseException {

        String newToken = TokenService.refreshToken(authorization);
        response.setHeader(Constants.HEADER, newToken);

        Date start = startTime != null ? DATETIME_FORMAT.parse(startTime) : null;
        Date end = endTime != null ? DATETIME_FORMAT.parse(endTime) : null;
        return Result.success(submitRecordService.getSubmitRecordWithStats(
                start, end, callee, mcc, mnc,
                innerReportState, innerReportError,
                destAccountStatus, pageNum, pageSize
        ));
    }

    /**
     * 导出短信发送记录Excel
     */
    @GetMapping("/export/easyexcel")
    public void easyExcelExport(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            @RequestParam String startTime,
            @RequestParam String endTime,
            @RequestParam(required = false) String callee,
            @RequestParam(required = false) String mcc,
            @RequestParam(required = false) String mnc,
            @RequestParam(required = false) String innerReportState,
            @RequestParam(required = false) String innerReportError,
            @RequestParam(required = false) String destAccountStatus,
            HttpServletResponse response) {

        String newToken = TokenService.refreshToken(authorization);
        response.setHeader(Constants.HEADER, newToken);

        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
            Date start = format.parse(startTime);
            Date end = format.parse(endTime);

            List<SubmitRecordExportVO> data = submitRecordService.queryExportDataForEasyExcel(
                    start, end, callee, mcc, mnc,
                    innerReportState, innerReportError, destAccountStatus
            );

            String fileName = URLEncoder.encode("SMS Send Records", "UTF-8").replace("+", "%20");
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Disposition", "attachment;filename*=UTF-8''" + fileName + ".xlsx");

            EasyExcel.write(response.getOutputStream(), SubmitRecordExportVO.class)
                    .sheet("SMS Send Records")
                    .doWrite(data);

        } catch (Exception e) {
            try {
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":500,\"msg\":\"Export failed\"}");
            } catch (Exception ex) {}
        }
    }
}