package com.texcellsms.sms.util;

import org.apache.commons.codec.binary.Hex;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 短信内容解析工具类（终极修复版：$ 符号正常显示）
 */
public class SmsContentParserUtil {

    private static final Base64.Encoder BASE64_ENCODER = Base64.getEncoder();

    /**
     * 根据DCS类型解析短信内容，并对结果进行BASE64加密
     */
    public static String parseAndEncryptShortMessage(String originalShortMessage, String dcs) {
        String parsedMsg = parseShortMessage(originalShortMessage, dcs);
        if (parsedMsg == null || parsedMsg.trim().isEmpty()) {
            return "";
        }
        return BASE64_ENCODER.encodeToString(parsedMsg.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 仅解析短信内容（用于导出）
     */
    public static String parseShortMessageOnly(String originalShortMessage, String dcs) {
        return parseShortMessage(originalShortMessage, dcs);
    }

    /**
     * 核心解析逻辑（已修复 $ 符号）
     */
    private static String parseShortMessage(String originalShortMessage, String dcs) {
        if (originalShortMessage == null || originalShortMessage.trim().isEmpty()) {
            return "";
        }

        try {
            byte[] hexBytes = Hex.decodeHex(originalShortMessage.toCharArray());

            // 你的数据就是标准 UTF-8 字符串！
            String content = new String(hexBytes, StandardCharsets.UTF_8);

            // 【关键修复】把数据库里的 0x02 字符替换成 $
            content = content.replace((char) 0x02, '$');

            return content;

        } catch (Exception e) {
            return ""; // 解析失败返回空，不显示乱码
        }
    }

    public static String genHmacSha256Hex(String username, String timestamp, String password) {
        try {
            String content = username + timestamp + password;
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));

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

    // 测试示例
    public static void main(String[] args) {
        String username = "CGerd";
        String timestamp = "1778468932945";
        String password = "xiongan1";

        String sign = genHmacSha256Hex(username, timestamp, password);
        System.out.println("签名：" + sign);
    }
}