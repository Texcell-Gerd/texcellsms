package com.texcellsms.sms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "mail")
public class MailProperties {

    /**
     * 发件邮箱
     */
    private String fromEmail;

    /**
     * SMTP授权码
     */
    private String authCode;

    /**
     * SMTP服务器
     */
    private String smtpHost;

    /**
     * SMTP端口
     */
    private String smtpPort;
}
