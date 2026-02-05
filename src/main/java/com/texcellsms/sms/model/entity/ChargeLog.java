package com.texcellsms.sms.model.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 充值/扣费日志表实体类（tbl_ChargeLog）
 * 对应账户金额变动记录
 */
@Schema(name = "ChargeLog", description = "充值/扣费日志表，记录账户金额变动明细")
@Data
public class ChargeLog {
    /**
     * 唯一标识
     */
    @Schema(description = "日志唯一标识ID", example = "1001", required = false)
    private Integer UNIID;

    /**
     * 记录创建时间（强制指定香港时间格式）
     */
    @Schema(description = "日志创建时间（香港时间）", example = "2026-02-05 10:00:00", required = true)
    @JsonFormat(
            pattern = "yyyy-MM-dd HH:mm:ss"
    )
    private Date CreateTime;

    /**
     * 操作人用户名
     */
    @Schema(description = "操作人用户名", example = "", required = true)
    private String UserName;

    /**
     * 操作的账户（手机号/账号）
     */
    @Schema(description = "用户", example = "", required = true)
    private String Account;

    /**
     * 金额变动值（正数为充值，负数为扣费）
     */
    @Schema(description = "金额变动值（正数充值，负数扣费）", example = "100.00", required = true)
    private BigDecimal Amount;

    /**
     * 变动前余额
     */
    @Schema(description = "变动前账户余额", example = "500.00", required = true)
    private BigDecimal PrevBalance;

    /**
     * 变动后余额
     */
    @Schema(description = "变动后账户余额", example = "600.00", required = true)
    private BigDecimal PostBalance;

    /**
     * 操作备注
     */
    @Schema(description = "操作备注说明", example = "充值100元，赠送10元", required = false)
    private String Remark;

    /**
     * 代理商ID
     */
    @Schema(description = "关联的代理商ID", example = "AGENT001", required = false)
    private String AgentID;
}