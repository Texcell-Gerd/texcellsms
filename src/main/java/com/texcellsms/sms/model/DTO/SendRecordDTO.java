package com.texcellsms.sms.model.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.util.Date;

/**
 * 发送记录查询结果DTO（含成本价换算+完整Swagger注解）
 */
@Data
@Schema(name = "SendRecordDTO", description = "短信发送记录查询结果（含成本价换算）")
public class SendRecordDTO {
    /**
     * 发送时间
     */
    @Schema(description = "短信发送时间", example = "2026-02-05 15:30:00", type = "string", format = "yyyy-MM-dd HH:mm:ss")
    private Date TransMsgTime;

    /**
     * 发送号码
     */
    @Schema(description = "发送号码（主叫号）", example = "85298765432")
    private String OriginalCaller;

    /**
     * 接收消息ID
     */
    @Schema(description = "接收消息唯一标识", example = "MSG20260205153000001")
    private String RecvMsgId;

    /**
     * 接收号码
     */
    @Schema(description = "接收号码（被叫号）", example = "85212345678")
    private String OriginalCallee;

    /**
     * 回执时间
     */
    @Schema(description = "短信状态回执时间", example = "2026-02-05 15:30:05", type = "string", format = "yyyy-MM-dd HH:mm:ss")
    private Date RecvDRTime;

    /**
     * 短信内容
     */
    @Schema(description = "短信发送内容", example = "您的验证码是123456，有效期5分钟")
    private String MsgContent;

    /**
     * MCC码
     */
    @Schema(description = "移动国家码（MCC）", example = "454")
    private String MCC;

    /**
     * MNC码
     */
    @Schema(description = "移动网络码（MNC）", example = "01")
    private String MNC;

    /**
     * 发送结果
     */
    @Schema(description = "短信发送状态", example = "DELIVRD", allowableValues = {"DELIVRD", "REJECTD", "UNDELIV"})
    private String InnerReportState;

    /**
     * 错误码
     */
    @Schema(description = "发送失败错误码", example = "088")
    private String InnerReportError;

    /**
     * 用户账号
     */
    @Schema(description = "发送用户账号（权限过滤字段）", example = "USER001")
    private String SourAccount;

    /**
     * 原始成本价（未换算）
     */
    @Schema(description = "原始成本价（需除以10000得到真实值）", example = "500")
    private Integer ChargeFlag;

    /**
     * 真实成本价（ChargeFlag/10000）
     */
    @Schema(description = "真实成本价（自动换算：ChargeFlag÷10000）", example = "0.05")
    private Double ActualCost;
}