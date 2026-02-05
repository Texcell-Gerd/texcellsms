package com.texcellsms.sms.util;

import java.math.BigDecimal;
import java.nio.charset.Charset;

/**
 * 最终精简版：仅保留备注解码 + 余额换算
 */
public class ChargeLogUtils {

    /**
     * 十六进制→GBK中文（JDK 8原生）
     */
    public static String hexToChinese(String hexStr) {
        if (hexStr == null || hexStr.trim().isEmpty()) {
            return "";
        }

        try {
            byte[] bytes = new byte[hexStr.length() / 2];
            for (int i = 0; i < hexStr.length(); i += 2) {
                bytes[i / 2] = (byte) Integer.parseInt(hexStr.substring(i, i + 2), 16);
            }
            return new String(bytes, Charset.forName("GBK"));

        } catch (Exception e) {
            System.err.println("解码异常：" + e.getMessage());
            return hexStr;
        }
    }

    /**
     * 余额换算（除以10000）
     */
    public static BigDecimal convertBalance(BigDecimal balance) {
        if (balance == null) {
            return BigDecimal.ZERO;
        }
        return balance.divide(new BigDecimal("10000"), 4, BigDecimal.ROUND_HALF_UP);
    }
}