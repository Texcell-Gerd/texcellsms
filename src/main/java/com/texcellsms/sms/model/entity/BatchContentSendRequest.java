package com.texcellsms.sms.model.entity;

import lombok.Data;

import java.util.List;

@Data
public class BatchContentSendRequest {
    private String scheduleTime;
    private String taskName;
    private String caller;
    private String preTaskId;

    // 号码列表
    private List<String> numbers;
    // 每个号码对应的内容，顺序与 numbers 一致
    private List<String> contents;
}