package com.texcellsms.sms.util;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;
import java.security.MessageDigest;

@Component
public class CustomerApiSignUtil {

    // 生成签名
    public static String createSign(String username, String timestamp, String password) {
        String plainText = username + timestamp + password;
        return md5(plainText);
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
        if (!serverSign.equalsIgnoreCase(sign)) {
            return AjaxResult.error(401, "Authentication failed: invalid signature");
        }

        return AjaxResult.success();
    }

    private static String md5(String str) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(str.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                String hex = Integer.toHexString(b & 0xFF);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
            return sb.toString().toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }
}