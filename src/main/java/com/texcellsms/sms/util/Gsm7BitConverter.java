package com.texcellsms.sms.util;

import java.util.*;
import java.util.stream.IntStream;

public class Gsm7BitConverter {

    private static final Map<Character, Integer> BASIC_MAP = new HashMap<>();
    private static final Map<Character, Integer> EXTENDED_MAP = new HashMap<>();
    private static final Map<Integer, Character> REVERSE_BASIC = new HashMap<>();
    private static final Map<Integer, Character> REVERSE_EXT = new HashMap<>();

    private static final int ESC = 0x1B;
    private static final int REPLACEMENT_CHAR = 0x3F;

    static {
        BASIC_MAP.put('@', 0x00);
        BASIC_MAP.put('£', 0x01);
        BASIC_MAP.put('$', 0x02);
        BASIC_MAP.put('¥', 0x03);
        BASIC_MAP.put('è', 0x04);
        BASIC_MAP.put('é', 0x05);
        BASIC_MAP.put('ù', 0x06);
        BASIC_MAP.put('ì', 0x07);
        BASIC_MAP.put('ò', 0x08);
        BASIC_MAP.put('Ç', 0x09);
        BASIC_MAP.put('\n', 0x0A);
        BASIC_MAP.put('Ø', 0x0B);
        BASIC_MAP.put('ø', 0x0C);
        BASIC_MAP.put('\r', 0x0D);
        BASIC_MAP.put('Å', 0x0E);
        BASIC_MAP.put('å', 0x0F);
        BASIC_MAP.put('Δ', 0x10);
        BASIC_MAP.put('_', 0x11);
        BASIC_MAP.put('Φ', 0x12);
        BASIC_MAP.put('Γ', 0x13);
        BASIC_MAP.put('Λ', 0x14);
        BASIC_MAP.put('Ω', 0x15);
        BASIC_MAP.put('Π', 0x16);
        BASIC_MAP.put('Ψ', 0x17);
        BASIC_MAP.put('Σ', 0x18);
        BASIC_MAP.put('Θ', 0x19);
        BASIC_MAP.put('Ξ', 0x1A);
        BASIC_MAP.put('Æ', 0x1C);
        BASIC_MAP.put('æ', 0x1D);
        BASIC_MAP.put('ß', 0x1E);
        BASIC_MAP.put('É', 0x1F);
        BASIC_MAP.put(' ', 0x20);
        BASIC_MAP.put('!', 0x21);
        BASIC_MAP.put('"', 0x22);
        BASIC_MAP.put('#', 0x23);
        BASIC_MAP.put('¤', 0x24);
        BASIC_MAP.put('%', 0x25);
        BASIC_MAP.put('&', 0x26);
        BASIC_MAP.put('\'', 0x27);
        BASIC_MAP.put('(', 0x28);
        BASIC_MAP.put(')', 0x29);
        BASIC_MAP.put('*', 0x2A);
        BASIC_MAP.put('+', 0x2B);
        BASIC_MAP.put(',', 0x2C);
        BASIC_MAP.put('-', 0x2D);
        BASIC_MAP.put('.', 0x2E);
        BASIC_MAP.put('/', 0x2F);
        for (int i = 0; i <= 9; i++) BASIC_MAP.put((char) ('0' + i), 0x30 + i);
        BASIC_MAP.put(':', 0x3A);
        BASIC_MAP.put(';', 0x3B);
        BASIC_MAP.put('<', 0x3C);
        BASIC_MAP.put('=', 0x3D);
        BASIC_MAP.put('>', 0x3E);
        BASIC_MAP.put('?', 0x3F);
        BASIC_MAP.put('¡', 0x40);
        for (int i = 0; i < 26; i++) BASIC_MAP.put((char) ('A' + i), 0x41 + i);
        BASIC_MAP.put('Ä', 0x5B);
        BASIC_MAP.put('Ö', 0x5C);
        BASIC_MAP.put('Ñ', 0x5D);
        BASIC_MAP.put('Ü', 0x5E);
        BASIC_MAP.put('§', 0x5F);
        BASIC_MAP.put('¿', 0x60);
        for (int i = 0; i < 26; i++) BASIC_MAP.put((char) ('a' + i), 0x61 + i);
        BASIC_MAP.put('ä', 0x7B);
        BASIC_MAP.put('ö', 0x7C);
        BASIC_MAP.put('ñ', 0x7D);
        BASIC_MAP.put('ü', 0x7E);
        BASIC_MAP.put('à', 0x7F);

        EXTENDED_MAP.put('^', 0x14);
        EXTENDED_MAP.put('{', 0x28);
        EXTENDED_MAP.put('}', 0x29);
        EXTENDED_MAP.put('[', 0x3C);
        EXTENDED_MAP.put(']', 0x3E);
        EXTENDED_MAP.put('~', 0x3D);
        EXTENDED_MAP.put('\\', 0x2F);
        EXTENDED_MAP.put('|', 0x40);
        EXTENDED_MAP.put('€', 0x65);

        for (Map.Entry<Character, Integer> e : BASIC_MAP.entrySet()) REVERSE_BASIC.put(e.getValue(), e.getKey());
        for (Map.Entry<Character, Integer> e : EXTENDED_MAP.entrySet()) REVERSE_EXT.put(e.getValue(), e.getKey());
    }

    public static byte[] utf8ToGsm7Bit(String text) {
        if (text == null || text.isEmpty()) return new byte[0];
        List<Integer> septets = new ArrayList<>();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp > 0xFFFF) {
                septets.add(REPLACEMENT_CHAR);
                continue;
            }
            char c = (char) cp;
            if (BASIC_MAP.containsKey(c)) {
                septets.add(BASIC_MAP.get(c));
            } else if (EXTENDED_MAP.containsKey(c)) {
                septets.add(ESC);
                septets.add(EXTENDED_MAP.get(c));
            } else {
                septets.add(REPLACEMENT_CHAR);
            }
        }
        byte[] out = new byte[septets.size()];
        for (int i = 0; i < septets.size(); i++) out[i] = (byte) (septets.get(i) & 0xFF);
        return out;
    }

    public static String gsm7BitToUtf8(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        for (byte b : bytes) {
            int v = b & 0xFF;
            if (esc) {
                sb.append(REVERSE_EXT.getOrDefault(v, '?'));
                esc = false;
            } else {
                if (v == ESC) {
                    esc = true;
                } else {
                    sb.append(REVERSE_BASIC.getOrDefault(v, '?'));
                }
            }
        }
        return sb.toString();
    }
}