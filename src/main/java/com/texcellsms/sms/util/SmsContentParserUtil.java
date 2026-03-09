package com.texcellsms.sms.util;

import org.apache.commons.codec.binary.Hex;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 短信内容解析工具类（替代SQL中的CASE逻辑 + BASE64加密）
 */
public class SmsContentParserUtil {

    // JDK自带BASE64编码器（线程安全）
    private static final Base64.Encoder BASE64_ENCODER = Base64.getEncoder();

    /**
     * 根据DCS类型解析短信内容，并对结果进行BASE64加密（用于接口返回）
     * @param originalShortMessage 原始16进制字符串（数据库中的Short_Message）
     * @param dcs DCS编码类型（0/1/8）
     * @return 解析后BASE64加密的短信内容
     */
    public static String parseAndEncryptShortMessage(String originalShortMessage, String dcs) {
        // 1. 解析短信内容
        String parsedMsg = parseShortMessage(originalShortMessage, dcs);
        // 2. 对解析结果进行BASE64加密
        if (parsedMsg == null || parsedMsg.trim().isEmpty()) {
            return "";
        }
        return BASE64_ENCODER.encodeToString(parsedMsg.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 仅解析短信内容（不加密，用于导出场景）
     * @param originalShortMessage 原始16进制字符串
     * @param dcs DCS编码类型
     * @return 解析后的明文短信内容
     */
    public static String parseShortMessageOnly(String originalShortMessage, String dcs) {
        return parseShortMessage(originalShortMessage, dcs);
    }

    /**
     * 基础解析逻辑（无加密）
     */
    private static String parseShortMessage(String originalShortMessage, String dcs) {
        // 空值处理
        if (originalShortMessage == null || originalShortMessage.trim().isEmpty()) {
            return "";
        }

        try {
            // 16进制字符串转字节数组（对应SQL的UNHEX函数）
            byte[] hexBytes = Hex.decodeHex(originalShortMessage.toCharArray());

            // 根据DCS类型解析
            switch (dcs) {
                case "0":
                    // DCS=0：默认字符集（CHAR）
                    return new String(hexBytes, StandardCharsets.ISO_8859_1);
                case "1":
                    // DCS=1：ASCII编码
                    return new String(hexBytes, StandardCharsets.US_ASCII);
                case "8":
                    // DCS=8：UCS2（UTF-16BE）编码
                    return new String(hexBytes, Charset.forName("UTF-16BE"));
                default:
                    // 其他类型直接返回原始值
                    return originalShortMessage;
            }
        } catch (Exception e) {
            // 解析失败返回原始值，避免业务中断
            return originalShortMessage;
        }
    }
}