package com.texcellsms.sms.model.DTO;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class DailyStatDTO {
    // 日期（yyyy-MM-dd）
    private String date;
    // 当日总提交数量
    private Integer submitCount;
    // 当日总消费金额（已 ÷10000）
    private BigDecimal totalCost;
}