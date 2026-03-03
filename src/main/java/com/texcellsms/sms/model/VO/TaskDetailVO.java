package com.texcellsms.sms.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "队列任务详情VO")
public class TaskDetailVO {
    @Schema(description = "编号")
    private String submitMsgId;
    @Schema(description = "发件人")
    private String caller;
    @Schema(description = "接收号码")
    private String callee;
    @Schema(description = "完成时间")
    private String recvDRTime;
    @Schema(description = "发送结果")
    private String recvDRStatus;
    @Schema(description = "原因说明")
    private String recvDRError;
    @Schema(description = "计费数量")
    private Integer pkNumber;
    @Schema(description = "拆分索引")
    private Integer pkTotal;
}