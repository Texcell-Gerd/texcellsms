package com.texcellsms.sms.util;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 全局短信接口响应枚举
 * 兼容旧代码 + 规范优化版
 */
@Getter
@AllArgsConstructor
public enum SMSResponseMessage {

    // ==================== 成功 ====================
    SUCCESS("Operation successful", 0),

    // ==================== 通用错误 ====================
    ERROR("Operation ERROR", 301),
    PARAMETERERROR("The parameters are incomplete.", 405),
    REQUESTERROR("illegal request", 400),

    // ==================== 缓存/DB ====================
    CACHEDBERROR("CacheDB stored procedure execution failed", 502),

    // ==================== 认证/权限 ====================
    AUTHORIZATIONERROR("Authentication failed", 401),
    AUTHORIZATIONTIMEOUT("Authentication timed out", 101),
    ACCOUNTERROR("The account name or password is incorrect. Please contact the administrator", 402),
    ACCOUNTLOCKEDERROR("The account is locked", 603),
    AUTHENTICATIONERROR("Failed to obtain two-step authentication data.", 403),
    IPERROR("Bound IP does not match", 403),
    IPAUTHORIZATIONERROR("Bound IP does not match", 101),
    IPLOCKEDERROR("The IP has been locked due to excessive failed attempts.", 480),
    IPWHHITLIST("Please contact the administrator to add IPs to the whitelist.", 481),
    LOGIN2FA("Perform two-step verification",201),
    TWOSTEPVERIFICATIONFAILED("Two-step verification code mismatch",482),
    BIND2AT("Please bind your account first.",483),


    // ==================== 新增：查询相关错误（以下为新增，不改动原有） ====================
    /**
     * 查询时间范围超限
     */
    QUERY_TIME_RANGE_EXCEEDED("Query time range cannot exceed 3 days", 410),

    /**
     * 查询数据量过大
     */
    QUERY_DATA_TOO_LARGE("Query data is too large, please narrow the query scope", 411),

    /**
     * 导出数据量过大
     */
    EXPORT_DATA_TOO_LARGE("Export data is too large, please narrow the export scope (max 50000 records)", 412),

    /**
     * 查询超时
     */
    QUERY_TIMEOUT("Query timeout, please try again later or narrow the query scope", 413),

    /**
     * 开始时间不能大于结束时间
     */
    START_TIME_AFTER_END_TIME("Start time cannot be greater than end time", 414),

    /**
     * 时间参数无效
     */
    INVALID_TIME_PARAMETER("Invalid time parameter", 415),

    /**
     * 查询结果为空
     */
    QUERY_RESULT_EMPTY("No data found for the query criteria", 416),

    ;

    /**
     * 响应消息
     */
    private final String message;

    /**
     * 响应码
     */
    private final int code;

    /**
     * 【兼容旧版】数据字段（实际不使用，仅保留结构）
     */
    private Object date;

    // 兼容你原来的 2 参构造
    SMSResponseMessage(String message, int code) {
        this.message = message;
        this.code = code;
        this.date = null;
    }
}