package com.texcellsms.sms.util;

public class SmsContentUtil {

    public static boolean isAllGsm7(String content) {
        if (content == null || content.isEmpty()) return true;
        for (char c : content.toCharArray()) {
            if (!Utf8ToGsm7.isGsmChar(c)) return false;
        }
        return true;
    }

    public static int calculateDCS(String content) {
        return isAllGsm7(content) ? 0 : 8;
    }

    public static int calcRealLength(String content) {
        int dcs = calculateDCS(content);
        return dcs == 0 ? Utf8ToGsm7.getGsm7Length(content) : content.length();
    }

    public static int getMaxPerSms(int dcs) {
        return dcs == 8 ? 70 : 160;
    }

    public static int getSplitPerSms(int dcs) {
        return dcs == 8 ? 67 : 153;
    }

    public static int calcSplitCount(String content) {
        int dcs = calculateDCS(content);
        int len = calcRealLength(content);
        int max = getMaxPerSms(dcs);
        if (len <= max) return 1;
        return (int) Math.ceil((double) len / getSplitPerSms(dcs));
    }
}