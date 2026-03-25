package com.texcellsms.sms.util;

import java.util.HashMap;
import java.util.Map;

/**
 * UTF-8 转 GSM-7 编码工具类（支持扩展表 + bit packing）
 * 适用于 SMS 发送（PDU 模式）
 */
public class Utf8ToGsm7 {

    // GSM 7-bit 默认字母表映射: Unicode char -> GSM septet 值 (0-127)
    private static final Map<Character, Integer> GSM_MAP = new HashMap<>();
    // 扩展表: Unicode char -> GSM 扩展 septet 值 (0-127)，实际发送时前面加 0x1B
    private static final Map<Character, Integer> GSM_EXT_MAP = new HashMap<>();

    static {
        // ==================== 基本 GSM 7-bit 字母表 ====================
        String basic = "@£$¥èéùìòÇ\"Øø\"ÅåΔ_ΦΓΛΩΠΨΣΘΞ\"xA0ÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
        for (int i = 0; i < basic.length(); i++) {
            GSM_MAP.put(basic.charAt(i), i);
        }

        // 部分特殊字符补充（避免编码问题）
        GSM_MAP.put('\f', 0x1B); // Form feed 在扩展表，但有些实现放基本
        // 注意：有些字符如 € 在扩展表

        // ==================== 扩展 GSM 7-bit 字母表 ====================
        GSM_EXT_MAP.put('|', 0x40);
        GSM_EXT_MAP.put('^', 0x14);
        GSM_EXT_MAP.put('€', 0x65);
        GSM_EXT_MAP.put('{', 0x28);
        GSM_EXT_MAP.put('}', 0x29);
        GSM_EXT_MAP.put('[', 0x5B);  // 有些实现用 0x1B 0x3C 等，这里用标准
        GSM_EXT_MAP.put(']', 0x5D);
        GSM_EXT_MAP.put('~', 0x3D);
        GSM_EXT_MAP.put('\\', 0x2F); // 反斜杠在扩展
        GSM_EXT_MAP.put('\f', 0x0A); // Form feed (有时放扩展)
        // 可继续补充其他扩展字符...
    }

    /**
     * 将 UTF-8 字符串转换为 GSM-7 打包后的 byte[]（可直接用于 SMS User Data）
     * @param text 输入的 UTF-8 字符串
     * @return 打包后的 8-bit 字节数组
     */
    public static byte[] encode(String text) {
        if (text == null || text.isEmpty()) {
            return new byte[0];
        }

        // 第一步：将每个字符转为 GSM septet 列表（可能包含 ESC）
        java.util.List<Integer> septets = new java.util.ArrayList<>();

        for (char c : text.toCharArray()) {
            if (GSM_MAP.containsKey(c)) {
                septets.add(GSM_MAP.get(c));
            } else if (GSM_EXT_MAP.containsKey(c)) {
                septets.add(0x1B);               // ESC
                septets.add(GSM_EXT_MAP.get(c));
            } else {
                // 不支持的字符替换为 '?' 
                septets.add(GSM_MAP.getOrDefault('?', 0x3F));
            }
        }

        // 第二步：7-bit septets 打包成 8-bit bytes
        int septetCount = septets.size();
        int byteCount = (septetCount * 7 + 7) / 8;  // 向上取整
        byte[] bytes = new byte[byteCount];

        int bitIndex = 0;
        for (int septet : septets) {
            int bytePos = bitIndex / 8;
            int bitPos = bitIndex % 8;

            bytes[bytePos] |= (septet << bitPos) & 0xFF;

            if (bitPos + 7 > 8) {
                // 跨字节
                bytes[bytePos + 1] |= (septet >> (8 - bitPos)) & 0xFF;
            }

            bitIndex += 7;
        }

        return bytes;
    }

    /**
     * 将 UTF-8 字符串转换为未打包的 septets 列表（调试或需要 septet 计数时使用）
     */
    public static java.util.List<Integer> toSeptets(String text) {
        java.util.List<Integer> septets = new java.util.ArrayList<>();
        for (char c : text.toCharArray()) {
            if (GSM_MAP.containsKey(c)) {
                septets.add(GSM_MAP.get(c));
            } else if (GSM_EXT_MAP.containsKey(c)) {
                septets.add(0x1B);
                septets.add(GSM_EXT_MAP.get(c));
            } else {
                septets.add(0x3F); // '?'
            }
        }
        return septets;
    }

    /**
     * 计算 GSM-7 编码后的字符数（用于 SMS 分段）
     * 扩展字符计为 2 个 septets
     */
    public static int getGsm7Length(String text) {
        int length = 0;
        for (char c : text.toCharArray()) {
            if (GSM_MAP.containsKey(c)) {
                length += 1;
            } else if (GSM_EXT_MAP.containsKey(c)) {
                length += 2;
            } else {
                length += 1; // '?' 
            }
        }
        return length;
    }

    // ====================== 测试示例 ======================
    public static void main(String[] args) {
        String test = "@[]";

        System.out.println("原始长度: " + test.length());
        System.out.println("GSM7 septets 长度: " + getGsm7Length(test));

        byte[] gsmBytes = encode(test);
        System.out.println("打包后字节长度: " + gsmBytes.length);
        for (byte b : gsmBytes) {
            System.out.printf("%02X ", b);
        }
        System.out.println();
    }
}