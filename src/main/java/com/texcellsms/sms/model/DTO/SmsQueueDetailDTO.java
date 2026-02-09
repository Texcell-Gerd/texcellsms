package com.texcellsms.sms.model.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.util.List;

/**
 * 短信队列详情返回DTO
 */
@Data
@Schema(name = "SmsQueueDetailDTO", description = "指定队列（秒级）的明细数据")
public class SmsQueueDetailDTO {
    @Schema(description = "返回码（默认0）", example = "0")
    private Integer code = 0;

    @Schema(description = "错误信息（默认null）", example = "null")
    private String ERROR = null;

    @Schema(description = "时区（默认UTC+8:00）", example = "UTC+8:00")
    private String timeZone = "UTC+8:00";

    @Schema(description = "队列时间（秒级）", example = "2026/02/09 13:04:55")
    private String submitMsgTime;

    @Schema(description = "队列明细列表")
    private List<SmsQueueDetailItemDTO> details;
}