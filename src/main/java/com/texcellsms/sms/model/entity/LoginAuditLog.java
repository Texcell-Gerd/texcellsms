package com.texcellsms.sms.model.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;

/**
 * 登录审计日志实体
 * 对应表名：tbl_Login_Audit_Log
 */
public class LoginAuditLog {

    private Long id;
    private String logUuid;
    private String username;
    private String realIp;
    private String sourceDomain;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime loginTime;

    private Integer loginResult;  // 0-失败, 1-成功
    private String resultCode;
    private String resultMessage;

    // CacheDB返回信息
    private String cacheDbWhitelistIp;
    private String cacheDbFaKey;
    private Integer cacheDbIsEnable;
    private String cacheDbRawResult;

    // 请求信息
    private String requestMethod;
    private String requestUri;
    private String userAgent;
    private String referer;

    // IP头信息
    private String cfConnectingIp;
    private String xRealIp;
    private String xForwardedFor;
    private String remoteAddr;

    // 扩展字段
    private String extInfo;
    private String remark;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    // Getters and Setters...
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getLogUuid() { return logUuid; }
    public void setLogUuid(String logUuid) { this.logUuid = logUuid; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getRealIp() { return realIp; }
    public void setRealIp(String realIp) { this.realIp = realIp; }

    public String getSourceDomain() { return sourceDomain; }
    public void setSourceDomain(String sourceDomain) { this.sourceDomain = sourceDomain; }

    public LocalDateTime getLoginTime() { return loginTime; }
    public void setLoginTime(LocalDateTime loginTime) { this.loginTime = loginTime; }

    public Integer getLoginResult() { return loginResult; }
    public void setLoginResult(Integer loginResult) { this.loginResult = loginResult; }

    public String getResultCode() { return resultCode; }
    public void setResultCode(String resultCode) { this.resultCode = resultCode; }

    public String getResultMessage() { return resultMessage; }
    public void setResultMessage(String resultMessage) { this.resultMessage = resultMessage; }

    public String getCacheDbWhitelistIp() { return cacheDbWhitelistIp; }
    public void setCacheDbWhitelistIp(String cacheDbWhitelistIp) { this.cacheDbWhitelistIp = cacheDbWhitelistIp; }

    public String getCacheDbFaKey() { return cacheDbFaKey; }
    public void setCacheDbFaKey(String cacheDbFaKey) { this.cacheDbFaKey = cacheDbFaKey; }

    public Integer getCacheDbIsEnable() { return cacheDbIsEnable; }
    public void setCacheDbIsEnable(Integer cacheDbIsEnable) { this.cacheDbIsEnable = cacheDbIsEnable; }

    public String getCacheDbRawResult() { return cacheDbRawResult; }
    public void setCacheDbRawResult(String cacheDbRawResult) { this.cacheDbRawResult = cacheDbRawResult; }

    public String getRequestMethod() { return requestMethod; }
    public void setRequestMethod(String requestMethod) { this.requestMethod = requestMethod; }

    public String getRequestUri() { return requestUri; }
    public void setRequestUri(String requestUri) { this.requestUri = requestUri; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public String getReferer() { return referer; }
    public void setReferer(String referer) { this.referer = referer; }

    public String getCfConnectingIp() { return cfConnectingIp; }
    public void setCfConnectingIp(String cfConnectingIp) { this.cfConnectingIp = cfConnectingIp; }

    public String getXRealIp() { return xRealIp; }
    public void setXRealIp(String xRealIp) { this.xRealIp = xRealIp; }

    public String getXForwardedFor() { return xForwardedFor; }
    public void setXForwardedFor(String xForwardedFor) { this.xForwardedFor = xForwardedFor; }

    public String getRemoteAddr() { return remoteAddr; }
    public void setRemoteAddr(String remoteAddr) { this.remoteAddr = remoteAddr; }

    public String getExtInfo() { return extInfo; }
    public void setExtInfo(String extInfo) { this.extInfo = extInfo; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}