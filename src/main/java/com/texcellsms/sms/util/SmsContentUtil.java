package com.texcellsms.sms.util;

public class SmsContentUtil {

    // 匹配 4 种变量规则
    private static final java.util.regex.Pattern VAR_PATTERN =
            java.util.regex.Pattern.compile("\\$\\{(phone#\\d+|randstr#\\d+|randnum#\\d+|randstrnum#\\d+)\\}");

    public static boolean isAllGsm7(String content) {
        if (content == null || content.isEmpty()) return true;
        for (char c : content.toCharArray()) {
            if (!Utf8ToGsm7.isGsmChar(c)) return false;
        }
        return true;
    }

    public static int calculateDCS(String content) {
        if (content == null || content.isEmpty()) return 0;

        // 1. 含中文/emoji/非 GSM-7 字符 → UCS-2（不管有没有变量）
        for (char c : content.toCharArray()) {
            if (!Utf8ToGsm7.isGsmChar(c)) {
                return 8;
            }
        }

        // 2. 到这里说明全是 GSM-7 字符（含基本字符 + {}[]|~^\€ 扩展字符）
        //    - 如果含 ${...} 变量 → GSM-7（DCS=0），让网关识别 1B 28 / 1B 29
        //    - 如果含变量外的扩展字符 → 还是 UCS-2（避免网关不认 1B 转义）
        if (VAR_PATTERN.matcher(content).find()) {
            return 0;
        }

        // 3. 判断变量外有没有"孤立"的扩展字符
        for (char c : content.toCharArray()) {
            if (isExtendedGsmChar(c)) {
                return 8;   // 有扩展字符，但没有变量语法 → 走 UCS-2
            }
        }

        // 4. 纯 GSM-7 基本字符 → DCS=0
        return 0;
    }

    public static int getMaxPerSms(int dcs) {
        return dcs == 8 ? 70 : 160;
    }

    public static int getSplitPerSms(int dcs) {
        return dcs == 8 ? 67 : 153;
    }

    public static boolean isExtendedGsmChar(char c) {
        return c == '^' || c == '{' || c == '}' || c == '[' ||
                c == ']' || c == '~' || c == '\\' || c == '|' || c == '€';
    }

    public static int calcGsm7ByteLength(String content) {
        int len = 0;
        for (char c : content.toCharArray()) {
            if (Utf8ToGsm7.isGsmChar(c)) {
                len += isExtendedGsmChar(c) ? 2 : 1;
            } else {
                len += 2;
            }
        }
        return len;
    }

    // 【修复 3】GSM-7 分片数：首片 160，后续 153
    public static int calcSplitCountCorrect(String content) {
        int dcs = calculateDCS(content);
        if (dcs == 8) {
            int len = content.length();
            if (len <= 70) return 1;
            return (int) Math.ceil((double) len / 67);
        } else {
            int byteLen = calcGsm7ByteLength(content);
            if (byteLen <= 160) return 1;
            return 1 + (int) Math.ceil((double) (byteLen - 160) / 153);
        }
    }

    // 【新增】GSM-7 内容切分：首片 160，后续 153
    public static java.util.List<String> splitGsm7Content(String content, int splitCount) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (splitCount == 1) {
            parts.add(content);
            return parts;
        }
        int n = content.length();
        int start = 0;
        int firstLimit = 160;
        int nextLimit = 153;
        int limit = firstLimit;

        while (start < n) {
            int end = start, septets = 0;
            while (end < n) {
                int cost = isExtendedGsmChar(content.charAt(end)) ? 2 : 1;
                if (septets + cost > limit) break;
                septets += cost;
                end++;
            }
            if (end == start) end = start + 1;
            parts.add(content.substring(start, end));
            start = end;
            limit = nextLimit;  // 后续片按 153
        }
        return parts;
    }
}