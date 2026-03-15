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