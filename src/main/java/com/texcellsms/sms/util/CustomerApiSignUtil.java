package com.texcellsms.sms.util;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Component
public class CustomerApiSignUtil {

    // ====================== HMAC-SHA256 → 直接返回十六进制 ======================
    public static String createSign(String username, String timestamp, String password) {
        try {
            String plainText = username + timestamp + password;

            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);

            byte[] hash = mac.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // 直接转十六进制
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                String h = Integer.toHexString(b & 0xFF);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();

        } catch (Exception e) {
            return "";
        }
    }

    // 验证签名
    public static AjaxResult verifySign(String username, String timestamp, String sign, String password) {
        if (StrUtil.hasBlank(username, timestamp, sign, password)) {
            return AjaxResult.error(400, "Required parameters are missing");
        }

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

        // 不区分大小写比对
        if (!serverSign.equalsIgnoreCase(sign)) {
            return AjaxResult.error(401, "Authentication failed: invalid signature");
        }

        return AjaxResult.success();
    }
}