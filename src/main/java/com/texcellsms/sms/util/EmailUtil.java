package com.texcellsms.sms.util;

import com.texcellsms.sms.config.MailProperties;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Properties;
import java.util.Random;

@Component
public class EmailUtil {

    @Resource
    private MailProperties mailConfig;

    /**
     * 生成6位验证码
     */
    public String generateCode() {

        Random random = new Random();

        int code = 100000 + random.nextInt(900000);

        return String.valueOf(code);
    }

    /**
     * 发送验证码邮件
     */
    public boolean sendEmailCode(String toEmail, String code) {

        try {

            Properties props = new Properties();

            // SMTP服务器
            props.put("mail.smtp.host", mailConfig.getSmtpHost());

            // SMTP端口
            props.put("mail.smtp.port", mailConfig.getSmtpPort());

            // 开启认证
            props.put("mail.smtp.auth", "true");

            // SSL
            props.put("mail.smtp.ssl.enable", "true");

            // 创建Session
            Session session = Session.getInstance(props, new Authenticator() {

                @Override
                protected PasswordAuthentication getPasswordAuthentication() {

                    return new PasswordAuthentication(
                            mailConfig.getFromEmail(),
                            mailConfig.getAuthCode()
                    );
                }
            });

            // 调试模式
            session.setDebug(false);

            // 创建邮件对象
            MimeMessage message = new MimeMessage(session);

            // 发件人
            message.setFrom(
                    new InternetAddress(mailConfig.getFromEmail())
            );

            // 收件人
            message.setRecipient(
                    Message.RecipientType.TO,
                    new InternetAddress(toEmail)
            );

            // 邮件标题
            message.setSubject(
                    "TEXCELLSMS Email Verification",
                    "UTF-8"
            );

            // 邮件内容
            String content =
                    "<div style='max-width:600px;margin:auto;padding:30px;" +
                            "font-family:Arial,sans-serif;background:#f4f6f9;border-radius:10px'>" +

                            "<div style='text-align:center'>" +
                            "<h2 style='color:#409EFF'>TEXCELLSMS Email Verification</h2>" +
                            "</div>" +

                            "<p>Hello,</p>" +

                            "<p>Your verification code is:</p>" +

                            "<div style='text-align:center;margin:30px 0'>" +
                            "<span style='display:inline-block;padding:15px 30px;" +
                            "font-size:32px;font-weight:bold;color:#ffffff;" +
                            "background:#409EFF;border-radius:8px;letter-spacing:5px'>" +
                            code +
                            "</span>" +
                            "</div>" +

                            "<p>This verification code will expire in 5 minutes.</p>" +

                            "<p>If you did not request this code, please ignore this email.</p>" +

                            "<br>" +

                            "<p>Best regards,<br>TEXCELLSMS</p>" +

                            "</div>";

            // HTML格式
            message.setContent(content, "text/html;charset=UTF-8");

            // 发送邮件
            Transport.send(message);

            return true;

        } catch (Exception e) {

            e.printStackTrace();

            return false;
        }
    }
}



