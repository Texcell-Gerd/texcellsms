package com.texcellsms.sms.util;

public class SmsContentUtil {

    // 判断内容是否全部支持 GSM7
    public static boolean isAllGsm7(String content) {
        if (content == null || content.isEmpty()) {
            return true;
        }
        for (char c : content.toCharArray()) {
            if (!Utf8ToGsm7.isGsmChar(c)) {
                return false;
            }
        }
        return true;
    }

    // 计算 DCS：0=GSM7，8=UCS2（中文/特殊语言）
    public static int calculateDCS(String content) {
        return isAllGsm7(content) ? 0 : 8;
    }

    // 计算真实短信长度
    public static int calcRealLength(String content) {
        int dcs = calculateDCS(content);
        if (dcs == 0) {
            // GSM7 长度计算
            int len = 0;
            for (char c : content.toCharArray()) {
                if (Utf8ToGsm7.isGsmChar(c)) {
                    len++;
                }
            }
            return len;
        } else {
            return content.length();
        }
    }

    // 单条短信最大长度
    public static int getMaxPerSms(int dcs) {
        return dcs == 8 ? 70 : 160;
    }

    // 长短信拆分后单条长度
    public static int getSplitPerSms(int dcs) {
        return dcs == 8 ? 67 : 153;
    }

    // 计算需要拆分几条短信
    public static int calcSplitCount(String content) {
        int dcs = calculateDCS(content);
        int len = calcRealLength(content);
        int max = getMaxPerSms(dcs);
        if (len <= max) {
            return 1;
        }
        return (int) Math.ceil((double) len / getSplitPerSms(dcs));
    }
}