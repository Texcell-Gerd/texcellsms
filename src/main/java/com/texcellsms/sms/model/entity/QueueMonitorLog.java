package com.texcellsms.sms.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class QueueMonitorLog {
    private Long id;
    private String taskId;
    private String subAccount;
    private String taskName;
    private Integer totalCount;
    private Integer submitSuccCount;
    private Integer drSuccCount;
    private Integer drFailCount;
    private Integer pendingCount;
    private Integer detailPendingCount;
    private LocalDateTime taskTime;
    private Integer elapsedMinutes;
    private String alertLevel;
    private Integer alertSent;
    private String status;
    private String processedBy;
    private LocalDateTime processedTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    private Boolean feeExist;
    private String feeExistDetail;
}