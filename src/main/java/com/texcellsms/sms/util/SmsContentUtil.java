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

    // 单条短信最大长度
    public static int getMaxPerSms(int dcs) {
        return dcs == 8 ? 70 : 160;
    }

    // 长短信拆分后单条长度
    public static int getSplitPerSms(int dcs) {
        return dcs == 8 ? 67 : 153;
    }


    // 判断是否为扩展字符
    public static boolean isExtendedGsmChar(char c) {
        return c == '^' || c == '{' || c == '}' || c == '[' ||
                c == ']' || c == '~' || c == '\\' || c == '|' || c == '€';
    }

    // 计算GSM7编码后的实际字节长度
    public static int calcGsm7ByteLength(String content) {
        int len = 0;
        for (char c : content.toCharArray()) {
            if (Utf8ToGsm7.isGsmChar(c)) {
                len += isExtendedGsmChar(c) ? 2 : 1;
            } else {
                // 非GSM字符，按UCS-2处理
                len += 2;
            }
        }
        return len;
    }

    // 正确计算分片数
    public static int calcSplitCountCorrect(String content) {
        int dcs = calculateDCS(content);

        if (dcs == 8) {
            // UCS-2: 单条70字符，长短信67字符
            int len = content.length();
            if (len <= 70) return 1;
            return (int) Math.ceil((double) len / 67);
        } else {
            // GSM7: 单条160字节，长短信153字节
            int byteLen = calcGsm7ByteLength(content);
            if (byteLen <= 160) return 1;  // ✅ 160字节以内不分片
            return (int) Math.ceil((double) (byteLen - 160) / 153) + 1;
            // 或者更清晰的计算：
            // return (int) Math.ceil((double) byteLen / 153);
            // 注意：这样对于 158 字节会返回 2，但实际上不需要分片
        }
    }
}