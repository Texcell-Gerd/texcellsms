package com.texcellsms.sms.model.DTO;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 分析页查询结果集DTO
 */
public class PItemDTO {

    @Schema(description = "时间", example = "2026-03-01")
    private String ReportDate;

    @Schema(description = "MCC", example = "724")
    private String MCC;

    @Schema(description = "MNC", example = "00")
    private String MNC;

    @Schema(description = "提交数量", example = "8")
    private Long SubmitCount;

    @Schema(description = "送达数量", example = "8")
    private Long DelivrdCount;

    @Schema(description = "失败数量", example = "0")
    private Long FailCount;

    @Schema(description = "超时数量", example = "0")
    private Long ExpireCount;

    @Schema(description = "未计费数量", example = "0")
    private Long NilChargeCount;

    @Schema(description = "更新时间", example = "2026-03-01")
    private String UpdateTime;

    @Schema(description = "Cost", example = "消费余额")
    private Long Cost;




}
