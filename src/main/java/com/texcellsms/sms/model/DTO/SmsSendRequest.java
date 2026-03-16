package com.texcellsms.sms.model.DTO;

import lombok.Data;

import java.util.List;

@Data
public class SmsSendRequest {
    private String scheduleTime; // 计划发送时间（可选）
    private String taskName;     // 任务名称（可选）
    private String preTaskId;    // 预任务ID（和上传二选一）
    private List<String> numbers;// 手动输入号码（和预任务二选一）
    private String caller;       // 发件人ID（可选）
    private String content;      // 短信内容（必填）
}