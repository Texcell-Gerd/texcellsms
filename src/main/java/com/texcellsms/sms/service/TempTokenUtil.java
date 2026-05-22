package com.texcellsms.sms.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;

public class TempTokenUtil {

    // 服务端固定密钥
    private static final String SERVER_SECRET = "2*oGKa31*Cl37O_#KVh2v3DcvP5^ReV^4E^GFRV#@8t9d6n!rJ";

    // Token 有效期（秒）
    private static final long TOKEN_VALIDITY_SECONDS = 60;

    /**
     * 生成临时 Token（包含账号、密码、时间戳、签名）
     * @param username 账号
     * @param password 密码（明文存储于 Token 中，请注意风险）
     * @return Base64 编码的 Token
     */
    public static String generateToken(String username, String password) {
        long timestamp = Instant.now().getEpochSecond();
        String data = username + ":" + password + ":" + timestamp;
        String signature = hmacSha256Hex(data, SERVER_SECRET);
        String rawToken = data + ":" + signature;
        return Base64.getEncoder().encodeToString(rawToken.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验证 Token 是否在有效期内（且未被篡改）
     * @param token Base64 编码的 Token
     * @return true 表示 Token 有效且未过期
     */
    public static boolean verifyTokenTimestamp(String token) {
        try {
            String decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":");
            if (parts.length != 4) return false;

            String username = parts[0];
            String password = parts[1];
            long timestamp = Long.parseLong(parts[2]);
            String providedSignature = parts[3];

            // 检查签名是否一致（防篡改）
            String data = username + ":" + password + ":" + timestamp;
            String expectedSignature = hmacSha256Hex(data, SERVER_SECRET);
            if (!expectedSignature.equals(providedSignature)) {
                return false;
            }

            // 检查时间戳是否在有效期内（只检查是否过期，不检查未来时间）
            long now = Instant.now().getEpochSecond();
            return now <= timestamp + TOKEN_VALIDITY_SECONDS;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从 Token 中提取账号
     * @param token Base64 编码的 Token
     * @return 账号，若 token 格式无效返回 null
     */
    public static String getUsernameFromToken(String token) {
        try {
            String decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":");
            if (parts.length < 2) return null;
            return parts[0];
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 Token 中提取密码
     * @param token Base64 编码的 Token
     * @return 密码，若 token 格式无效返回 null
     */
    public static String getPasswordFromToken(String token) {
        try {
            String decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":");
            if (parts.length < 3) return null;
            return parts[1];
        } catch (Exception e) {
            return null;
        }
    }

    // HMAC-SHA256 计算并返回十六进制字符串（便于比较）
    private static String hmacSha256Hex(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC error", e);
        }
    }

    // 测试示例
//    public static void main(String[] args) throws InterruptedException {
//        String username = "alice";
//        String password = "mySecret123";
//
//        String token = generateToken(username, password);
//        System.out.println("Token: " + token);
//        System.out.println("提取账号: " + getUsernameFromToken(token));
//        System.out.println("提取密码: " + getPasswordFromToken(token));
//        System.out.println("Token 是否有效(未过期): " + verifyTokenTimestamp(token));
//
//        // 等待 61 秒后再次验证
//        Thread.sleep(61000);
//        System.out.println("等待 61 秒后验证: " + verifyTokenTimestamp(token));
//    }
}