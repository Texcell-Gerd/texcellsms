package com.texcellsms.sms.model.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 短信队列列表项（按秒分组的统计信息）
 */
@Data
@Schema(name = "SmsQueueItemDTO", description = "单条队列（秒级）的统计信息")
public class SmsQueueItemDTO {
    @Schema(description = "提交时间（秒级）", example = "2026/02/09 13:04:55")
    private String submitMsgTime;

    @Schema(description = "计费数量（默认1）", example = "1")
    private Integer chargeCount = 1;

    @Schema(description = "该队列总条数", example = "50")
    private Long totalCount;

    @Schema(description = "发送条数（同总条数）", example = "50")
    private Long sendCount;

    @Schema(description = "提交成功数量", example = "50")
    private Long submitSuccessCount;

    @Schema(description = "送达成功数量（DELIVRD）", example = "48")
    private Long deliverSuccessCount;

    @Schema(description = "送达失败数量（非DELIVRD）", example = "2")
    private Long deliverFailCount;

    @Schema(description = "短信内容（解码后）", example = "您的验证码是123456")
    private String msg;
}