package com.texcellsms.sms.model.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.util.List;

/**
 * 短信队列列表返回DTO（含分页+统计）
 */
@Data
@Schema(name = "SmsQueueDTO", description = "短信队列列表（按秒分组）+ 分页统计信息")
public class SmsQueueDTO {
    @Schema(description = "返回码（默认0）", example = "0")
    private Integer code = 0;

    @Schema(description = "错误信息（默认null）", example = "null")
    private String ERROR = null;

    @Schema(description = "时区（默认UTC+8:00）", example = "UTC+8:00")
    private String timeZone = "UTC+8:00";

    @Schema(description = "当前页码", example = "1")
    private Integer page;

    @Schema(description = "每页条数（默认10，最大50）", example = "10")
    private Integer pageSize;

    @Schema(description = "总记录数", example = "120")
    private Long total;

    @Schema(description = "总页数", example = "12")
    private Integer pageCount;

    @Schema(description = "队列列表项")
    private List<SmsQueueItemDTO> items;
}