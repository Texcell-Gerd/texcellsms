package com.texcellsms.sms.model.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import nonapi.io.github.classgraph.json.Id;

import java.util.Date;

@Data
@Schema(name = "tbl_APIScheduleSend")
public class SmsScheduleSend {
    @Id
    private String taskId;
    private Date scheduleSendTime;
    private Byte scheduleStatus;
    private Integer calleeCount;
    private Integer splitCount;
    private Integer totalCount;
    private Date taskTime;
    private String taskIp;
    private String subAccount;
    private String taskTimeZone;
    private String shortMessage;
    private String taskName;
    private Integer submitSuccCount;
    private Integer submitFailCount;
    private Integer drSuccCount;
    private Integer drFailCount;
}
