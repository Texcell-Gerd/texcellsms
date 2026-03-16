package com.texcellsms.sms.model.entity;


import lombok.Data;
import java.util.Date;

@Data
public class SmsScheduleNumberTask {
    private String subAccount;
    private String submitTime;   // String
    private String preTaskId;
    private String validTill;    // String（时间戳）
}