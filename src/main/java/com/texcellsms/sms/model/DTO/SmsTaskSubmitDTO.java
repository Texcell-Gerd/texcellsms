package com.texcellsms.sms.model.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 提交发送任务DTO
 */
@Data
@Schema(name = "SmsTaskSubmitDTO", description = "短信任务提交参数")
public class SmsTaskSubmitDTO {
    @Schema(description = "预任务ID（上传文件时生成）", example = "20260308022728472600070003")
    private String preTaskId;

    @Schema(description = "计划发送时间（yyyy-MM-dd HH:mm:ss）", example = "2026-03-08 10:00:00")
    private String scheduleSendTime;

    @Schema(description = "任务名称", example = "营销短信_20260308")
    private String taskName;

    @Schema(description = "发件人ID", example = "SENDER001")
    private String callerId;

    @Schema(description = "短信内容", example = "短信内容")
    private String content;

    // 可选：如果前端直接传号码列表，也可以接收
    @Schema(description = "接收号码列表（和preTaskId二选一）")
    private List<String> callee;
}