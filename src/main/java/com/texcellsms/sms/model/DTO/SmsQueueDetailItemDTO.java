package com.texcellsms.sms.model.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 短信队列明细项
 */
@Data
@Schema(name = "SmsQueueDetailItemDTO", description = "单条短信的送达明细")
public class SmsQueueDetailItemDTO {
    @Schema(description = "提交消息ID（编号）", example = "73ceb50e")
    private String submitMsgId;

    @Schema(description = "接收号码", example = "5562994549550")
    private String callee;

    @Schema(description = "回执状态（发送结果）", example = "DELIVRD")
    private String innerReportState;

    @Schema(description = "完成时间", example = "2026-02-09 13:18:09")
    private String recvDRTime;

    @Schema(description = "原因说明", example = "000")
    private String innerReportError;
}