package com.texcellsms.sms.util;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class CustomerApiSignUtil {

    // ====================== HMAC-SHA256 ======================
    public static String createSign(String username, String timestamp, String password) {
        try {
            // 拼接规则：username + timestamp + password
            String plainText = username + timestamp + password;

            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);

            byte[] hash = mac.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            // 返回 Base64 编码（行业标准）
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            return "";
        }
    }

    // 验证签名
    public static AjaxResult verifySign(String username, String timestamp, String sign, String password) {
        if (StrUtil.hasBlank(username, timestamp, sign, password)) {
            return AjaxResult.error(400, "Required parameters are missing");
        }
        // 修复URL传输中 + 号变空格的问题
        sign = sign.replace(" ", "+");

        long reqTime;
        try {
            reqTime = Long.parseLong(timestamp);
        } catch (Exception e) {
            return AjaxResult.error(400, "Invalid timestamp, must be number");
        }

        long reqTimeSeconds = (timestamp.length() == 10) ? reqTime : reqTime / 1000;
        long nowSeconds = System.currentTimeMillis() / 1000;

        if (Math.abs(nowSeconds - reqTimeSeconds) > 300) {
            return AjaxResult.error(401, "Request expired");
        }

        String serverSign = createSign(username, timestamp, password);
        if (!serverSign.equals(sign)) {
            return AjaxResult.error(401, "Authentication failed: invalid signature");
        }

        return AjaxResult.success();
    }
}