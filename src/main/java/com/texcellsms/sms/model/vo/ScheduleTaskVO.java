package com.texcellsms.sms.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "队列任务列表VO")
public class ScheduleTaskVO {
    @Schema(description = "任务名称")
    private String taskName;
    @Schema(description = "任务ID")
    private String taskId;
    @Schema(description = "任务提交时间")
    private String taskTime;
    @Schema(description = "任务预定时间")
    private String scheduleSendTime;
    @Schema(description = "时区")
    private String taskTimeZone;
    @Schema(description = "发送状态")
    private Byte scheduleStatus;
    @Schema(description = "总条数")
    private Integer totalCount;
    @Schema(description = "发送数量")
    private Integer calleeCount;
    @Schema(description = "提交成功数量")
    private Integer submitSuccCount;
    @Schema(description = "送达成功数量")
    private Integer drSuccCount;
    @Schema(description = "送达失败数量")
    private Integer drFailCount;
    @Schema(description = "短信内容")
    private String shortMessage;
    @Schema(description = "计费数量")
    private Integer splitCount;
}