package com.texcellsms.sms.constant;

/**
 * 数据源常量（对应application.yml中的数据源名称）
 */
public class DataSourceConstants {
    // 主业务库
    public static final String DS_ECCOM = "eccom";       // Global.texcellsms.com
    public static final String DS_PREPAY = "prepay";     // Bulksms.texcellsms.com
    public static final String DATASOURCE_PREPAY2 = "prepay2";

    // CacheDB库
    public static final String DS_CACHE_AUTH = "cacheAuth"; // 登录/余额/改密码
    public static final String DS_CACHE_RATE = "cacheRate"; // 费率查询
}