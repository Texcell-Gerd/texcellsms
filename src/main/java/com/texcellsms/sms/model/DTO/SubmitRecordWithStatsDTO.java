package com.texcellsms.sms.model.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.util.List;

/**
 * 发送记录+统计信息 统一返回实体
 */
@Data
@Schema(name = "SubmitRecordWithStatsDTO", description = "发送记录列表+统计信息")
public class SubmitRecordWithStatsDTO {
    // 1. 发送记录列表（原结果集）
    @Schema(description = "发送记录列表")
    private List<SubmitRecordDTO> records;

    // 2. 提交统计
    @Schema(description = "提交总数")
    private Long submitTotal; // 总提交数
    @Schema(description = "提交失败数（默认0）")
    private Long submitFailCount = 0L;
    @Schema(description = "提交成功率（默认100%）")
    private String submitSuccessRate = "100.00%";

    // 3. 送达统计
    @Schema(description = "送达成功数")
    private Long deliverSuccessCount = 0L;
    @Schema(description = "送达失败数")
    private Long deliverFailCount = 0L;
    @Schema(description = "送达成功率（格式：00.00%）")
    private String deliverSuccessRate = "0.00%";

    // 4. 分页信息
    @Schema(description = "总页数")
    private Integer pageCount;
    @Schema(description = "当前页")
    private Integer currentPage;
    @Schema(description = "页大小")
    private Integer pageSize;
}