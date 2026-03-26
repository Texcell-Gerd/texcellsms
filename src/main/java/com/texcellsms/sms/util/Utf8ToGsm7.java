package com.texcellsms.sms.util;

import java.util.HashMap;
import java.util.Map;

public class Utf8ToGsm7 {

    private static final Map<Character, Integer> GSM_MAP = new HashMap<>();
    private static final Map<Character, Integer> GSM_EXT_MAP = new HashMap<>();

    static {
        String basic = "@£$¥èéùìòÇØøÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
        for (int i = 0; i < basic.length(); i++) {
            GSM_MAP.put(basic.charAt(i), i);
        }

        GSM_EXT_MAP.put('|', 0x40);
        GSM_EXT_MAP.put('^', 0x14);
        GSM_EXT_MAP.put('€', 0x65);
        GSM_EXT_MAP.put('{', 0x28);
        GSM_EXT_MAP.put('}', 0x29);
        GSM_EXT_MAP.put('[', 0x5B);
        GSM_EXT_MAP.put(']', 0x5D);
        GSM_EXT_MAP.put('~', 0x3D);
        GSM_EXT_MAP.put('\\', 0x2F);
    }

    public static boolean isGsmChar(char c) {
        return GSM_MAP.containsKey(c) || GSM_EXT_MAP.containsKey(c);
    }

    public static byte[] encode(String text) {
        if (text == null || text.isEmpty()) {
            return new byte[0];
        }

        java.util.List<Integer> septets = new java.util.ArrayList<>();
        for (char c : text.toCharArray()) {
            if (GSM_MAP.containsKey(c)) {
                septets.add(GSM_MAP.get(c));
            } else if (GSM_EXT_MAP.containsKey(c)) {
                septets.add(0x1B);
                septets.add(GSM_EXT_MAP.get(c));
            } else {
                septets.add(0x3F);
            }
        }

        int septetCount = septets.size();
        int byteCount = (septetCount * 7 + 7) / 8;
        byte[] bytes = new byte[byteCount];

        int bitIndex = 0;
        for (int septet : septets) {
            int bytePos = bitIndex / 8;
            int bitPos = bitIndex % 8;

            bytes[bytePos] |= (septet << bitPos) & 0xFF;
            if (bitPos + 7 > 8) {
                bytes[bytePos + 1] |= (septet >> (8 - bitPos)) & 0xFF;
            }
            bitIndex += 7;
        }
        return bytes;
    }

    public static int getGsm7Length(String text) {
        int length = 0;
        for (char c : text.toCharArray()) {
            if (GSM_MAP.containsKey(c)) length += 1;
            else if (GSM_EXT_MAP.containsKey(c)) length += 2;
            else length += 1;
        }
        return length;
    }
}