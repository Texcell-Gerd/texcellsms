package com.texcellsms.sms.model.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 登录审计日志实体
 * 对应表名：tbl_Login_Audit_Log
 */
@Data
public class LoginAuditLog {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 日志唯一标识(UUID)
     */
    private String logUuid;

    /**
     * 登录账号
     */
    private String username;

    /**
     * 真实客户端IP(支持IPv6)
     */
    private String realIp;

    /**
     * IP所在国家
     */
    private String ipCountry;

    /**
     * IP所在省份/地区
     */
    private String ipRegion;

    /**
     * IP所在城市
     */
    private String ipCity;

    /**
     * IP运营商
     */
    private String ipIsp;

    /**
     * IP完整地理位置
     */
    private String ipLocation;

    /**
     * 请求来源域名(host头)
     */
    private String sourceDomain;

    /**
     * 登录时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime loginTime;

    /**
     * 登录结果: 0-失败, 1-成功
     */
    private Integer loginResult;

    /**
     * 返回结果码
     */
    private String resultCode;

    /**
     * 结果描述信息
     */
    private String resultMessage;

    /**
     * CacheDB返回的白名单IP
     */
    private String cacheDbWhitelistIp;

    /**
     * CacheDB返回的FAKey(两步验证Key)
     */
    private String cacheDbFaKey;

    /**
     * CacheDB返回的是否启用(0-禁用,1-启用)
     */
    private Integer cacheDbIsEnable;

    /**
     * CacheDB原始返回结果码
     */
    private String cacheDbRawResult;

    /**
     * 认证类型：0-普通登录 1-谷歌验证 2-邮箱验证
     */
    private Integer authType;

    /**
     * 两步验证类型: GOOGLE-谷歌验证, EMAIL-邮箱验证
     */
    private String twoFactorType;

    /**
     * 两步验证目标(邮箱地址或谷歌密钥Key)
     */
    private String twoFactorTarget;

    /**
     * 二步验证结果：0-未验证 1-成功 2-失败
     */
    private Integer twoFaResult;

    /**
     * 登录场景：DEFAULT/2FA_REQUIRED/2FA_SUCCESS/2FA_FAILED/LOGIN_SUCCESS
     */
    private String loginScene;

    /**
     * 请求方法(POST/GET等)
     */
    private String requestMethod;

    /**
     * 请求URI
     */
    private String requestUri;

    /**
     * 客户端User-Agent
     */
    private String userAgent;

    /**
     * 请求来源页
     */
    private String referer;

    /**
     * Cloudflare传递的真实IP
     */
    private String cfConnectingIp;

    /**
     * Nginx透传的X-Real-IP
     */
    private String xRealIp;

    /**
     * X-Forwarded-For原始值
     */
    private String xForwardedFor;

    /**
     * TCP连接的RemoteAddr
     */
    private String remoteAddr;

    /**
     * 登录耗时(毫秒)
     */
    private Integer loginDuration;

    /**
     * 扩展信息(JSON格式)
     */
    private String extInfo;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

}