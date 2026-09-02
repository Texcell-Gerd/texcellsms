package com.texcellsms.sms.model.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class CustomerDrAutoFixConfig {
    private Long id;
    private String subAccount;
    private Integer enabled;
    private Integer timeoutMinutes;
    private Integer maxRetryCount;
    private String scheduleCron;
    private String scheduleTimezone;
    private Integer minTotalCount;
    private Integer maxPendingCount;
    private Integer notifyBeforeFix;
    private Integer notifyAfterFix;
    private String notifyWebhook;
    private String remark;
    private String createdBy;
    private LocalDateTime createdTime;
    private String updatedBy;
    private LocalDateTime updatedTime;

    // 扩展字段（不存数据库）
    private Integer totalCount;        // 当前任务总数
    private Integer pendingCount;      // 当前未回执数
    private Integer elapsedMinutes;    // 已过时间
}