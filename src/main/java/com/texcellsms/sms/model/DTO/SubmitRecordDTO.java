package com.texcellsms.sms.model.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 发送记录查询结果DTO（Submit_Bill主表）
 */
@Data
@Schema(name = "SubmitRecordDTO", description = "短信发送记录查询结果（Submit_Bill主表+Fee_Bill副表关联）")
public class SubmitRecordDTO {
    @Schema(description = "提交时间", example = "2026/02/05 12:35:36")
    private String SubmitMsgTime;

    @Schema(description = "发件人号码", example = "85298765432")
    private String Caller;

    @Schema(description = "被叫号码", example = "85212345678")
    private String Callee;

    @Schema(description = "短信长度", example = "45")
    private String SM_Length;

    @Schema(description = "计费金额（原始值，需除以10000）", example = "500")
    private String ChargeFee;

    @Schema(description = "真实计费金额（ChargeFee÷10000）", example = "0.05")
    private Double ActualChargeFee;

    @Schema(description = "短信内容（解码后）", example = "您的验证码是123456")
    private String Short_Message;

    @Schema(description = "移动国家码（MCC）", example = "454")
    private String MCC;

    @Schema(description = "移动网络码（MNC）", example = "01")
    private String MNC;

    @Schema(description = "消息ID", example = "SUBMIT20260205123536001")
    private String SubmitMsgId;

    @Schema(description = "回执状态（DELIVRD/REJECTD/UNDELIV/已提交）", example = "DELIVRD")
    private String InnerReportState;

    @Schema(description = "回执状态码", example = "088")
    private String InnerReportError;

    @Schema(description = "回执完成时间", example = "2026/02/05 12:35:47")
    private String RecvDRTime;

    @Schema(description = "用户账号", example = "根据当前登录账户查询发送记录")
    private String SourAccount;
}