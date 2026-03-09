package com.texcellsms.sms.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * BASE64加密解密工具类
 * 支持字符串/字节数组加密，处理空值和异常
 */
public class Base64Util {

    // JDK自带BASE64编码器/解码器（线程安全，可全局复用）
    private static final Base64.Encoder ENCODER = Base64.getEncoder();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    /**
     * 字符串BASE64加密（默认UTF-8编码，适配短信内容加密场景）
     * @param content 待加密字符串（可为null）
     * @return 加密后的BASE64字符串，null/空字符串返回空字符串
     */
    public static String encrypt(String content) {
        // 空值处理：避免空指针异常
        if (content == null || content.trim().isEmpty()) {
            return "";
        }
        try {
            // 以UTF-8编码为字节数组，避免中文/特殊字符乱码
            byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
            // BASE64加密并转换为字符串
            return ENCODER.encodeToString(contentBytes);
        } catch (Exception e) {
            // 异常兜底：加密失败返回空字符串，避免业务中断
            // 可根据需要改为抛自定义异常
            return "";
        }
    }

    /**
     * 字符串BASE64解密（默认UTF-8编码）
     * @param encryptedContent 加密后的BASE64字符串（可为null）
     * @return 解密后的原字符串，null/空字符串返回空字符串
     */
    public static String decrypt(String encryptedContent) {
        // 空值处理
        if (encryptedContent == null || encryptedContent.trim().isEmpty()) {
            return "";
        }
        try {
            // BASE64解密为字节数组
            byte[] decryptedBytes = DECODER.decode(encryptedContent);
            // 转换为UTF-8字符串
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 处理非法BASE64字符串（如格式错误）
            return "";
        } catch (Exception e) {
            // 其他异常兜底
            return "";
        }
    }

    /**
     * 字节数组BASE64加密（扩展方法，适用于文件/二进制数据）
     * @param bytes 待加密字节数组
     * @return 加密后的BASE64字符串，null返回空字符串
     */
    public static String encrypt(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        try {
            return ENCODER.encodeToString(bytes);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 字节数组BASE64解密（扩展方法）
     * @param encryptedContent 加密后的BASE64字符串
     * @return 解密后的字节数组，异常返回空数组
     */
    public static byte[] decryptToBytes(String encryptedContent) {
        if (encryptedContent == null || encryptedContent.trim().isEmpty()) {
            return new byte[0];
        }
        try {
            return DECODER.decode(encryptedContent);
        } catch (Exception e) {
            return new byte[0];
        }
    }
}