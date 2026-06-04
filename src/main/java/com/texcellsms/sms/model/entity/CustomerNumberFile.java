package com.texcellsms.sms.model.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class CustomerNumberFile {
    private Long id;
    private String taskId;
    private String username;
    private String fileName;
    private String filePath;
    private Long fileSize;
    private Integer numberCount;
    private LocalDateTime createTime;
}