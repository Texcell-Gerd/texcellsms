package com.texcellsms.sms.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

// FeatureModule.java
@Data
public class FeatureModule {
    private Long id;
    private String moduleCode;
    private String moduleName;
    private String moduleType;
    private Integer enabled;
    private Integer sortOrder;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
