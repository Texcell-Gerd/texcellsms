package com.texcellsms.sms.model.DTO;

import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class AnalyzeDTO {

    // 提交时间（你业务写的是MCC，我按你表字段ReportTime映射）
    private Date submitTime;

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

    // 更新时间
    private Date updateTime;

    // 消费金额（Java层 ÷10000）
    private BigDecimal totalCost;
}