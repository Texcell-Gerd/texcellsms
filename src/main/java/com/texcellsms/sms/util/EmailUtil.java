package com.texcellsms.sms.util;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Properties;
import java.util.Random;

public class EmailUtil {

    /**
     * 发件人邮箱
     */
    private static final String FROM_EMAIL = "report@texcellsms.com";

    /**
     * SMTP授权码
     * 注意不是邮箱密码
     */
    private static final String EMAIL_AUTH_CODE = "@Adsl888";

    /**
     * SMTP服务器
     */
    private static final String SMTP_HOST = "smtp.exmail.qq.com";

    /**
     * SMTP端口
     */
    private static final String SMTP_PORT = "465";

    /**
     * 生成6位验证码
     */
    public static String generateCode() {
        Random random = new Random();
        int code = 100000 + random.nextInt(900000);
        return String.valueOf(code);
    }

    /**
     * 发送验证码邮件
     *
     * @param toEmail 收件人邮箱
     * @param code 验证码
     * @return 是否发送成功
     */
    public static boolean sendEmailCode(String toEmail, String code) {

        try {
            Properties props = new Properties();

            // SMTP服务器
            props.put("mail.smtp.host", SMTP_HOST);

            // SMTP端口
            props.put("mail.smtp.port", SMTP_PORT);

            // 开启认证
            props.put("mail.smtp.auth", "true");

            // SSL
            props.put("mail.smtp.ssl.enable", "true");

            // 创建Session
            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(FROM_EMAIL, EMAIL_AUTH_CODE);
                }
            });

            // 开启调试（生产环境可以关闭）
            session.setDebug(false);

            // 创建邮件对象
            MimeMessage message = new MimeMessage(session);

            // 发件人
            message.setFrom(new InternetAddress(FROM_EMAIL));

            // 收件人
            message.setRecipient(Message.RecipientType.TO,
                    new InternetAddress(toEmail));

            // 邮件标题
            message.setSubject("TEXCELLSMS Email Verification Code");

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

            message.setContent(content, "text/html;charset=UTF-8");

            // 发送邮件
            Transport.send(message);

            return true;

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 测试
     */
//    public static void main(String[] args) {
//
//        // 收件邮箱
//        String toEmail = "edmond@texcellsms.com";
//
//        // 生成验证码
//        String code = generateCode();
//
//        // 发送
//        boolean result = sendEmailCode(toEmail, code);
//
//        if (result) {
//            System.out.println("发送成功");
//            System.out.println("验证码：" + code);
//        } else {
//            System.out.println("发送失败");
//        }
//    }
}



