package com.texcellsms.sms.model.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * API任务明细表实体类
 * 对应表：tbl_APITask
 */
@Data
@Schema(description = "API任务明细信息")
public class TblAPITask {

    @Schema(description = "标识ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long unid;

    @Schema(description = "任务ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String apiTaskId;

    @Schema(description = "SMSC返回的提交消息ID")
    private String submitMsgId;

    @Schema(description = "主叫", defaultValue = "")
    private String caller;

    @Schema(description = "被叫")
    private String callee;

    @Schema(description = "提交时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private String submitMsgTime;

    @Schema(description = "提交ACK", defaultValue = "0")
    private String submitAck;

    @Schema(description = "收到DR时间", defaultValue = "")
    private String recvDRTime;

    @Schema(description = "DR状态", defaultValue = "")
    private String recvDRStatus;

    @Schema(description = "DR错误码", defaultValue = "")
    private String recvDRError;

    @Schema(description = "提交帐号")
    private String subAccount;

    @Schema(description = "删除时间,timestamp类型.设计逻辑为提交后的72小时后删除", defaultValue = "0")
    private Long destroyTime;

    @Schema(description = "DR接收标识0-pending,1-已经收到DR", defaultValue = "0")
    private Byte dlrSendStatus;

    @Schema(description = "无用", defaultValue = "0")
    private Integer dlrTryTime;

    @Schema(description = "MCCMNC", defaultValue = "000000")
    private String vendorID;

    @Schema(description = "提交总数", defaultValue = "1")
    private Integer pkTotal;

    @Schema(description = "当前计数", defaultValue = "1")
    private Integer pkNumber;
}
