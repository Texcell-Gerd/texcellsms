package com.texcellsms.sms.util;

// 短信编码与计费计算工具
public class SmsContentUtil {
    // 判断是否包含中文/Unicode字符
    public static boolean hasUnicode(String content) {
        return content.chars().anyMatch(c -> c > 0xFF);
    }

    // 计算DCS：中文=8，英文=0
    public static int calculateDCS(String content) {
        return hasUnicode(content) ? 8 : 0;
    }

    // 计算单条短信最大字符数
    public static int getMaxCharCount(int dcs) {
        return dcs == 8 ? 70 : 160;
    }

    // 计算长短信单段字符数（UDHI模式）
    public static int getSplitCharCount(int dcs) {
        return dcs == 8 ? 67 : 153;
    }

    // 计算总计费条数（SplitCount）
    public static int calculateSplitCount(String content) {
        int dcs = calculateDCS(content);
        int maxChar = getMaxCharCount(dcs);
        int splitChar = getSplitCharCount(dcs);
        int len = content.length();
        if (len <= maxChar) return 1;
        return (int) Math.ceil((double) len / splitChar);
    }
}
