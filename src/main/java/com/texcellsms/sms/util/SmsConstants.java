package com.texcellsms.sms.util;

// 常量类
public interface SmsConstants {
    // 服务器路径
    String BASE_PATH = "D:\\SMSTask\\";
    String PHONE_PATH = BASE_PATH + "Phone\\";
    String TASK_PATH = BASE_PATH + "Task\\";
    // 最大号码数量
    int MAX_PHONE_COUNT = 200000;
    // 预任务有效期（分钟）
    int PRE_TASK_VALID_MINUTES = 15;
    // 默认发件人ID
    String DEFAULT_CALLER = "SMSINFO";
    // 默认任务名称
    String DEFAULT_TASK_NAME = "Default Task";
    // 默认时区
    String DEFAULT_TIMEZONE = "+08:00";
    // 默认发送状态
    byte DEFAULT_SCHEDULE_STATUS = 0;
}
