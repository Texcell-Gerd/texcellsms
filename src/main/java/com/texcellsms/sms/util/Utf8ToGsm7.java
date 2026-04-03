package com.texcellsms.sms.util;

import java.util.*;

public class Utf8ToGsm7 {

    private static final Map<Character, Integer> BASIC = new HashMap<>();
    private static final Map<Character, Integer> EXT   = new HashMap<>();
    private static final Map<Integer, Character> REV_BASIC = new HashMap<>();
    private static final Map<Integer, Character> REV_EXT   = new HashMap<>();

    private static final int ESC = 0x1B;
    private static final int BAD = 0x3F;

    static {
        BASIC.put('@', 0x00);
        BASIC.put('£', 0x01);
        BASIC.put('$', 0x02);
        BASIC.put('¥', 0x03);
        BASIC.put('è', 0x04);
        BASIC.put('é', 0x05);
        BASIC.put('ù', 0x06);
        BASIC.put('ì', 0x07);
        BASIC.put('ò', 0x08);
        BASIC.put('Ç', 0x09);
        BASIC.put('\n', 0x0A);
        BASIC.put('Ø', 0x0B);
        BASIC.put('ø', 0x0C);
        BASIC.put('\r', 0x0D);
        BASIC.put('Å', 0x0E);
        BASIC.put('å', 0x0F);
        BASIC.put('Δ', 0x10);
        BASIC.put('_', 0x11);
        BASIC.put('Φ', 0x12);
        BASIC.put('Γ', 0x13);
        BASIC.put('Λ', 0x14);
        BASIC.put('Ω', 0x15);
        BASIC.put('Π', 0x16);
        BASIC.put('Ψ', 0x17);
        BASIC.put('Σ', 0x18);
        BASIC.put('Θ', 0x19);
        BASIC.put('Ξ', 0x1A);
        BASIC.put('Æ', 0x1C);
        BASIC.put('æ', 0x1D);
        BASIC.put('ß', 0x1E);
        BASIC.put('É', 0x1F);
        BASIC.put(' ', 0x20);
        BASIC.put('!', 0x21);
        BASIC.put('"', 0x22);
        BASIC.put('#', 0x23);
        BASIC.put('¤', 0x24);
        BASIC.put('%', 0x25);
        BASIC.put('&', 0x26);
        BASIC.put('\'', 0x27);
        BASIC.put('(', 0x28);
        BASIC.put(')', 0x29);
        BASIC.put('*', 0x2A);
        BASIC.put('+', 0x2B);
        BASIC.put(',', 0x2C);
        BASIC.put('-', 0x2D);
        BASIC.put('.', 0x2E);
        BASIC.put('/', 0x2F);
        for (int i = 0; i <= 9; i++)
            BASIC.put((char) ('0' + i), 0x30 + i);
        BASIC.put(':', 0x3A);
        BASIC.put(';', 0x3B);
        BASIC.put('<', 0x3C);
        BASIC.put('=', 0x3D);
        BASIC.put('>', 0x3E);
        BASIC.put('?', 0x3F);
        BASIC.put('¡', 0x40);
        for (int i = 0; i < 26; i++)
            BASIC.put((char) ('A' + i), 0x41 + i);
        BASIC.put('Ä', 0x5B);
        BASIC.put('Ö', 0x5C);
        BASIC.put('Ñ', 0x5D);
        BASIC.put('Ü', 0x5E);
        BASIC.put('§', 0x5F);
        BASIC.put('¿', 0x60);
        for (int i = 0; i < 26; i++)
            BASIC.put((char) ('a' + i), 0x61 + i);
        BASIC.put('ä', 0x7B);
        BASIC.put('ö', 0x7C);
        BASIC.put('ñ', 0x7D);
        BASIC.put('ü', 0x7E);
        BASIC.put('à', 0x7F);

        EXT.put('^', 0x14);
        EXT.put('{', 0x28);
        EXT.put('}', 0x29);
        EXT.put('[', 0x3C);
        EXT.put(']', 0x3E);
        EXT.put('~', 0x3D);
        EXT.put('\\', 0x2F);
        EXT.put('|', 0x40);
        EXT.put('€', 0x65);

        for (Map.Entry<Character, Integer> e : BASIC.entrySet())
            REV_BASIC.put(e.getValue(), e.getKey());
        for (Map.Entry<Character, Integer> e : EXT.entrySet())
            REV_EXT.put(e.getValue(), e.getKey());
    }

    public static byte[] encode(String text) {
        if (text == null || text.isEmpty())
            return new byte[0];

        List<Integer> septets = new ArrayList<>();
        for (char c : text.toCharArray()) {
            if (BASIC.containsKey(c)) {
                septets.add(BASIC.get(c));
            } else if (EXT.containsKey(c)) {
                septets.add(ESC);
                septets.add(EXT.get(c));
            } else {
                septets.add(BAD);
            }
        }

        byte[] out = new byte[septets.size()];
        for (int i = 0; i < septets.size(); i++) {
            out[i] = (byte) (septets.get(i) & 0xFF);
        }
        return out;
    }

    public static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0)
            return "";

        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        for (byte b : bytes) {
            int v = b & 0xFF;
            if (esc) {
                sb.append(REV_EXT.getOrDefault(v, '?'));
                esc = false;
            } else {
                if (v == ESC) {
                    esc = true;
                } else {
                    sb.append(REV_BASIC.getOrDefault(v, '?'));
                }
            }
        }
        return sb.toString();
    }

    public static boolean isGsmChar(char c) {
        return BASIC.containsKey(c) || EXT.containsKey(c);
    }
}