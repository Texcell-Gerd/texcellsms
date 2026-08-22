package com.texcellsms.sms.util;

import cn.hutool.core.util.StrUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Component
public class CustomerApiSignUtil {

    private static final Logger logger = LoggerFactory.getLogger(CustomerApiSignUtil.class);

    /**
     * 生成 HMAC-SHA256 签名
     */
    public static String createSign(String username, String timestamp, String password) {
        try {
            String plainText = username + timestamp + password;

            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(
                    password.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            );
            mac.init(secretKey);

            byte[] hash = mac.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                String h = Integer.toHexString(b & 0xFF);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();

        } catch (Exception e) {
            logger.error("签名生成失败", e);
            return "";
        }
    }

    /**
     * 验证签名
     * @return 验证成功返回 null，失败返回 AjaxResult 错误对象
     */
    public static AjaxResult verifySign(String username, String timestamp, String sign, String password) {
        // 1. 参数检查
        if (StrUtil.hasBlank(username, timestamp, sign, password)) {
            return AjaxResult.error(400, "Required parameters are missing");
        }

        // 2. 时间戳验证
        long reqTime;
        try {
            reqTime = Long.parseLong(timestamp);
        } catch (Exception e) {
            return AjaxResult.error(400, "Invalid timestamp, must be number");
        }

        long reqTimeSeconds = (timestamp.length() == 10) ? reqTime : reqTime / 1000;
        
        long nowSeconds = System.currentTimeMillis() / 1000;

        if (Math.abs(nowSeconds - reqTimeSeconds) > 300) {
            logger.warn("请求过期 - username: {}, requestTime: {}, now: {}",
                    username, reqTimeSeconds, nowSeconds);
            return AjaxResult.error(401, "Request expired");
        }

        // 3. 生成服务端签名
        String serverSign = createSign(username, timestamp, password);

        // 4. 比对签名（不区分大小写）
        if (!serverSign.equalsIgnoreCase(sign)) {
            logger.warn("签名验证失败 - username: {}, serverSign: {}, clientSign: {}",
                    username, serverSign, sign);
            return AjaxResult.error(401, "Authentication failed: invalid signature");
        }

        // ✅ 验证成功返回 null
        logger.info("签名验证成功 - username: {}", username);
        return null;
    }
}