package com.texcellsms.sms.model.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class IpRateLimitConfig {
    private Long id;
    private String ip;
    private String customerName;
    private Integer rateLimit;
    private Integer blockDuration;
    private Integer enabled;
    private Integer priority;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String createBy;
    private String updateBy;
}