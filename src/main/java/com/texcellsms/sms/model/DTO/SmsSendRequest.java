package com.texcellsms.sms.model.DTO;

import com.fasterxml.jackson.annotation.JsonProperty;
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
    private String username;     // 提交账号
    private String timestamp;    // 时间戳
    private String sign;         // 签名
    private String fileName;     // 原始文件名（可选）

    // 切片发送参数
    @JsonProperty("SplitFlag")
    private String splitFlag;         // 0关闭切片 1开启切片

    @JsonProperty("SplitCount")
    private Integer splitCount;       // 每个片多少个号码

    @JsonProperty("SplitSleepSecond")
    private Integer splitSleepSecond; // 每次发送间隔时间（秒）
}