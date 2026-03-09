package com.texcellsms.sms.model.DTO;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 发送记录查询结果DTO（Submit_Bill主表）
 */
@Data
@Schema(name = "SubmitRecordDTO", description = "短信发送记录查询结果（Submit_Bill主表+Fee_Bill副表关联）")
public class SubmitRecordDTO {
    @Schema(description = "提交时间", example = "2026/02/05 12:35:36")
    private String submitMsgTime;

    @Schema(description = "发件人号码", example = "85298765432")
    private String caller;

    @Schema(description = "被叫号码", example = "85212345678")
    private String callee;

    @Schema(description = "短信长度", example = "45")
    private String smLength;

    @Schema(description = "计费金额（原始值，不返回）", hidden = true)
    @JsonIgnore // 关键：JSON序列化时忽略，彻底不返回
    private String chargeFee;

    @Schema(description = "真实计费金额（ChargeFee÷10000）", example = "0.0500")
    private String actualChargeFee;

    @Schema(description = "短信内容（解码后BASE64加密）", example = "5L2g5oiR57yW56CB5rWL6K+VMTIzNDU2")
    private String shortMessage;

    @Schema(description = "移动国家码（MCC）", example = "454")
    private String mcc;

    @Schema(description = "移动网络码（MNC）", example = "01")
    private String mnc;

    @Schema(description = "消息ID", example = "SUBMIT20260205123536001")
    private String submitMsgId;

    @Schema(description = "回执状态（DELIVRD/REJECTD/UNDELIV/已提交）", example = "DELIVRD")
    private String innerReportState;

    @Schema(description = "回执状态码", hidden = true) // Swagger隐藏
    @JsonIgnore // JSON序列化忽略
    private String innerReportError;

    @Schema(description = "回执完成时间", example = "2026/02/05 12:35:47")
    private String recvDRTime;

    @Schema(description = "用户账号", example = "根据当前登录账户查询发送记录")
    private String sourAccount;

    @Schema(description = "判断用", hidden = true) // Swagger隐藏
    @JsonIgnore // JSON序列化忽略
    private String fbInnerReportState;

    @Schema(description = "DCS编码类型", example = "8") // 放开Swagger注释，返回给前端
    private String dcs;

    @Schema(description = "原始加密短信内容（解析用，不返回）", hidden = true)
    @JsonIgnore // 序列化时忽略，不返回给前端
    private String originalShortMessage;
}