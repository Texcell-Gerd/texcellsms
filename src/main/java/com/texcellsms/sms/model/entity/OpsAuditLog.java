package com.texcellsms.sms.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OpsAuditLog {
    private Long id;
    private String operator;
    private String operationType;
    private String operationName;
    private String target;
    private Integer result;
    private String resultMsg;
    private String requestIp;
    private String userAgent;
    private LocalDateTime createTime;
}