package com.texcellsms.sms.controller;

import com.texcellsms.sms.model.DTO.SubmitRecordDTO;
import com.texcellsms.sms.service.SubmitRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Date;
import java.util.List;

@Tag(name = "发送记录接口（Submit_Bill主表）", description = "短信发送记录查询（Submit_Bill主表+Fee_Bill副表关联，默认1小时，最多3天）")
@RestController
@RequestMapping("/api/submit-record")
public class SubmitRecordController {

    @Resource
    private SubmitRecordService submitRecordService;

    // 线上版接口
    @Operation(
            summary = "【线上】查询当前登录用户发送记录",
            description = "1. 未传时间默认查最近1小时数据；2. 传时间最多返回近3天数据；3. 自动换算计费金额（ChargeFee÷10000）；4. 短信内容自动解码；5. 数据来源：CDR_DB库"
    )
    @Parameters({
            @Parameter(name = "startTime", description = "开始时间（格式：yyyy-MM-dd HH:mm:ss）",
                    in = ParameterIn.QUERY, required = false, example = "2026-02-05 14:00:00"),
            @Parameter(name = "endTime", description = "结束时间（格式：yyyy-MM-dd HH:mm:ss）",
                    in = ParameterIn.QUERY, required = false, example = "2026-02-05 15:00:00"),
            @Parameter(name = "callee", description = "被叫号码",
                    in = ParameterIn.QUERY, required = false, example = "85212345678"),
            @Parameter(name = "mcc", description = "移动国家码（MCC）",
                    in = ParameterIn.QUERY, required = false, example = "454"),
            @Parameter(name = "mnc", description = "移动网络码（MNC）",
                    in = ParameterIn.QUERY, required = false, example = "01"),
            @Parameter(name = "innerReportState", description = "回执状态（DELIVRD/REJECTD/UNDELIV/已提交）",
                    in = ParameterIn.QUERY, required = false, example = "DELIVRD"),
            @Parameter(name = "innerReportError", description = "回执状态码",
                    in = ParameterIn.QUERY, required = false, example = "088")
    })
    @GetMapping("/online/query")
    public List<SubmitRecordDTO> queryOnlineSubmitRecords(
            Principal principal,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date endTime,
            @RequestParam(required = false) String callee,
            @RequestParam(required = false) String mcc,
            @RequestParam(required = false) String mnc,
            @RequestParam(required = false) String innerReportState,
            @RequestParam(required = false) String innerReportError
    ) {
        String loginUserAccount = principal.getName();
        return submitRecordService.getSubmitRecordsByLoginUser(
                loginUserAccount, startTime, endTime,
                callee, mcc, mnc, innerReportState, innerReportError
        );
    }

    // 调试版接口
    @Operation(
            summary = "【调试】查询指定用户发送记录",
            description = "1. 未传时间默认查最近1小时数据；2. 传时间最多返回近3天数据；3. 自动换算计费金额（ChargeFee÷10000）；4. 短信内容自动解码；5. 仅用于开发/测试环境"
    )
    @Parameters({
            @Parameter(name = "sourAccount", description = "用户账号（SourAccount）",
                    in = ParameterIn.QUERY, required = false, example = "CTU506AT"),
            @Parameter(name = "startTime", description = "开始时间（格式：yyyy-MM-dd HH:mm:ss）",
                    in = ParameterIn.QUERY, required = false, example = "2026-02-05 14:00:00"),
            @Parameter(name = "endTime", description = "结束时间（格式：yyyy-MM-dd HH:mm:ss）",
                    in = ParameterIn.QUERY, required = false, example = "2026-02-05 15:00:00"),
            @Parameter(name = "callee", description = "被叫号码",
                    in = ParameterIn.QUERY, required = false, example = "85212345678"),
            @Parameter(name = "mcc", description = "移动国家码（MCC）",
                    in = ParameterIn.QUERY, required = false, example = "454"),
            @Parameter(name = "mnc", description = "移动网络码（MNC）",
                    in = ParameterIn.QUERY, required = false, example = "01"),
            @Parameter(name = "innerReportState", description = "回执状态（DELIVRD/REJECTD/UNDELIV/已提交）",
                    in = ParameterIn.QUERY, required = false, example = "DELIVRD"),
            @Parameter(name = "innerReportError", description = "回执状态码",
                    in = ParameterIn.QUERY, required = false, example = "088")
    })
    @GetMapping("/debug/query")
    public List<SubmitRecordDTO> queryDebugSubmitRecords(
            @RequestParam(required = false, defaultValue = "TEST_USER_DEFAULT") String sourAccount,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date endTime,
            @RequestParam(required = false) String callee,
            @RequestParam(required = false) String mcc,
            @RequestParam(required = false) String mnc,
            @RequestParam(required = false) String innerReportState,
            @RequestParam(required = false) String innerReportError
    ) {
        return submitRecordService.getSubmitRecordsForDebug(
                sourAccount, startTime, endTime,
                callee, mcc, mnc, innerReportState, innerReportError
        );
    }
}