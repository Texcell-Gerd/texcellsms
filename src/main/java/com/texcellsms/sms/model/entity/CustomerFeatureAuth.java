package com.texcellsms.sms.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

// CustomerFeatureAuth.java
@Data
public class CustomerFeatureAuth {
    private Long id;
    private String subAccount;
    private String moduleCode;
    private Integer enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
