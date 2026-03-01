package com.texcellsms.sms.model.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * API计划发送任务表实体类
 * 对应表：tbl_APIScheduleSend
 */
@Data
@Schema(description = "API计划发送任务信息")
public class TblAPIScheduleSend {

    @Schema(description = "任务ID,主键", requiredMode = Schema.RequiredMode.REQUIRED)
    private String taskID;

    @Schema(description = "计划发送时间，使用服务器时区", requiredMode = Schema.RequiredMode.REQUIRED)
    private String scheduleSendTime;

    @Schema(description = "任务状态：0-待发送 1-已发送 2-发送端读取文件失败 4-任务被删除 其他:发送端异常", defaultValue = "0")
    private Byte scheduleStatus;

    @Schema(description = "上传的被叫号码数量", defaultValue = "0")
    private Integer calleeCount;

    @Schema(description = "拆分条数", defaultValue = "0")
    private Integer splitCount;

    @Schema(description = "总计费条数", defaultValue = "0")
    private Integer totalCount;

    @Schema(description = "提交任务时间", defaultValue = "")
    private String taskTime;

    @Schema(description = "任务提交IP", defaultValue = "")
    private String taskIP;

    @Schema(description = "提交帐号", defaultValue = "")
    private String subAccount;

    @Schema(description = "任务时区", defaultValue = "+08:00")
    private String taskTimeZone;

    @Schema(description = "任务内容")
    private String shortMessage;

    @Schema(description = "任务名称", defaultValue = "")
    private String taskName;

    @Schema(description = "发送端提交成功数", defaultValue = "0")
    private Integer submitSuccCount;

    @Schema(description = "发送端提交失败数", defaultValue = "0")
    private Integer submitFailCount;

    @Schema(description = "DR成功数", defaultValue = "0")
    private Integer drSuccCount;

    @Schema(description = "DR失败数", defaultValue = "0")
    private Integer drFailCount;
}
