package com.texcellsms.sms.model.DTO;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class AnalyzeDTO {

    // 提交时间（表字段SubmitTime）
    private String submitTime;

    // MCC
    private String mcc;

    // MNC
    private String mnc;

    // 提交数量
    private Integer submitCount;

    // 送达数量
    private Integer succCount;

    // 失败数量
    private Integer failCount;

    // 超时数量
    private Integer expireCount;

    // 不计费数量
    private Integer nilChargeCount;

    // 更新时间(表字段是ReportTime)
    private String updateTime;

    // 消费金额（Java层 ÷10000）
    private BigDecimal totalCost;
}