package com.texcellsms.sms.model.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 费用账单表（Fee_Bill）
 * 存储短信/充值相关的费用账单记录
 */
@Schema(name = "FeeBill", description = "费用账单表，存储短信/充值相关的费用账单明细")
@Data
public class FeeBill {
    /** 事务确认标识 */
    @Schema(description = "事务确认唯一标识", example = "TRAN123456789", required = false)
    private String TransAck;

    /** 内部服务编码 */
    @Schema(description = "内部服务编码", example = "SMS001", required = true)
    private String InnerServiceCode;

    /** 服务类型 */
    @Schema(description = "服务类型", example = "短信发送", required = true)
    private String ServiceType;

    /** 费用用户类型 */
    @Schema(description = "费用用户类型", example = "普通用户", required = false)
    private String FeeUserType;

    /** 公司ID */
    @Schema(description = "所属公司ID", example = "COMP001", required = false)
    private String CompanyId;

    /** 费用编码 */
    @Schema(description = "费用类型编码", example = "FEE_SMS", required = true)
    private String FeeCode;

    /** 收费号码 */
    @Schema(description = "收费号码", example = "10690000", required = false)
    private String ChargeNum;

    /** 数据编码方案（DCS） */
    @Schema(description = "数据编码方案（DCS）", example = "0", required = false)
    private String DCS;

    /** 目标账户 */
    @Schema(description = "目标账户（接收方）", example = "13900139000", required = true)
    private String DestAccount;

    /** 源账户 */
    @Schema(description = "源账户（发送方）", example = "13800138000", required = true)
    private String SourAccount;

    /** 注册标识 */
    @Schema(description = "注册状态标识", example = "1", required = false)
    private String Register;

    /** 消息类型标识（MTMFlag） */
    @Schema(description = "消息类型标识（MTMFlag）", example = "MT", required = false)
    private String MTMFlag;

    /** 赠送值 */
    @Schema(description = "赠送金额/条数", example = "10.00", required = false)
    private String GiveValue;

    /** 服务上线标识 */
    @Schema(description = "服务上线状态标识", example = "1", required = false)
    private String ServiceUP;

    /** SPID（服务提供商ID） */
    @Schema(description = "服务提供商ID（SPID）", example = "SP001", required = false)
    private String SPID;

    /** 移动国家码（MCC） */
    @Schema(description = "移动国家码（MCC）", example = "460", required = false)
    private String MCC;

    /** 移动网络码（MNC） */
    @Schema(description = "移动网络码（MNC）", example = "01", required = false)
    private String MNC;

    /** 接收状态报告时间 */
    @Schema(description = "接收状态报告时间", example = "2026-02-05 11:00:00", required = false)
    private Date RecvDRTime;

    /** 报告消息ID */
    @Schema(description = "报告消息ID", example = "MSG123456", required = false)
    private String RecvMsgId;

    /** 原始报告状态 */
    @Schema(description = "原始报告状态", example = "DELIVRD", required = false)
    private String OrgiaReportState;

    /** 原始报告错误码 */
    @Schema(description = "原始报告错误码", example = "0", required = false)
    private String OrgiaReportError;

    /** 总包数 */
    @Schema(description = "短信分包总包数", example = "5", required = false)
    private Integer PKTotal;

    /** 当前包序号 */
    @Schema(description = "当前分包序号", example = "1", required = false)
    private Integer PKNumber;

    /** 内部报告状态 */
    @Schema(description = "内部处理报告状态", example = "成功", required = false)
    private String InnerReportState;

    /** 内部报告错误码 */
    @Schema(description = "内部报告错误码", example = "0000", required = false)
    private String InnerReportError;

    /** 提交日期 */
    @Schema(description = "账单提交日期", example = "2026-02-05 09:00:00", required = true)
    private Date SubmitDate;

    /** 完成日期 */
    @Schema(description = "账单完成日期", example = "2026-02-05 10:00:00", required = false)
    private Date DoneDate;

    /** 唯一标识 */
    @Schema(description = "账单唯一标识", example = "BILL123456789", required = false)
    private String UniKey;

    /** 内部消息ID */
    @Schema(description = "内部消息ID", example = "INNER123456", required = false)
    private String InnerMsgId;

    /** 原始主叫号码 */
    @Schema(description = "原始主叫号码", example = "13800138000", required = false)
    private String OriginalCaller;

    /** 目标地址类型 */
    @Schema(description = "目标地址类型", example = "手机号", required = false)
    private String DestAddrType;

    /** 原始被叫号码 */
    @Schema(description = "原始被叫号码", example = "13900139000", required = false)
    private String OriginalCallee;

    /** 消息类型（EsmClass） */
    @Schema(description = "消息类型（EsmClass）", example = "0", required = false)
    private String EsmClass;

    /** 接收消息时间 */
    @Schema(description = "接收消息时间", example = "2026-02-05 08:00:00", required = false)
    private Date RecvMsgTime;

    /** 转发消息时间 */
    @Schema(description = "转发消息时间", example = "2026-02-05 08:01:00", required = false)
    private Date TransMsgTime;

    /** 消息内容 */
    @Schema(description = "短信消息内容", example = "您的验证码是123456", required = false)
    private String MsgContent;

    /** 消息转发类型 */
    @Schema(description = "消息转发类型", example = "即时转发", required = false)
    private String MsgForwardType;

    /** 费用金额 */
    @Schema(description = "账单费用金额", example = "0.10", required = true)
    private BigDecimal FeeAmount;

    /** 折扣比例 */
    @Schema(description = "折扣比例", example = "0.9", required = false)
    private BigDecimal DiscountRate;

    /** 实际扣费金额 */
    @Schema(description = "实际扣费金额", example = "0.09", required = true)
    private BigDecimal ActualDeductAmount;

    /** 账单状态（0-未结算 1-已结算 2-已取消） */
    @Schema(description = "账单状态（0-未结算 1-已结算 2-已取消）", example = "1", required = true)
    private Integer BillStatus;

    /** 操作员ID */
    @Schema(description = "操作员工号", example = "OP001", required = false)
    private String OperatorId;

    /** 备注 */
    @Schema(description = "账单备注说明", example = "短信发送扣费", required = false)
    private String Remark;
}