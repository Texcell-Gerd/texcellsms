package com.texcellsms.sms.util;

import dev.samstevens.totp.code.*;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;

import java.util.Base64;

/**
 * Google两步认证工具类
 */
public class GoogleAuthUtil {

    // 生成密钥（初次登录）
    public static String generateSecret() {
        DefaultSecretGenerator secretGenerator = new DefaultSecretGenerator();
        return secretGenerator.generate();
    }

    // 生成二维码（返回Base64编码）
    public static String generateQrCode(String username) {
        try {
            String secret = generateSecret();
            QrData qrData = new QrData.Builder()
                    .label(username + "@texcellsms.com")
                    .secret(secret)
                    .issuer("TexcellSMS")
                    .algorithm(HashingAlgorithm.SHA1)
                    .digits(6)
                    .period(30)
                    .build();

            QrGenerator generator = new ZxingPngQrGenerator();
            byte[] qrCodeBytes = generator.generate(qrData);
            return Base64.getEncoder().encodeToString(qrCodeBytes);
        } catch (QrGenerationException e) {
            e.printStackTrace();
            return "";
        }
    }

    // 验证验证码
    public static boolean verifyCode(String username, String code) {
        // TODO: 从数据库获取用户绑定的secret
        String secret = generateSecret(); // 临时用新密钥，后续替换为用户绑定的

        TimeProvider timeProvider = new SystemTimeProvider();
        CodeGenerator codeGenerator = new DefaultCodeGenerator();
        CodeVerifier verifier = new DefaultCodeVerifier(codeGenerator, timeProvider);
        return verifier.isValidCode(secret, code);
    }
}