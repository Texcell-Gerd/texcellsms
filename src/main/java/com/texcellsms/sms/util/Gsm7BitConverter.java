import java.util.*;
import java.util.stream.IntStream;

/**
 * GSM 7-bit编码工具类
 * 支持将字符串编码为GSM 03.38标准的7-bit字节数组（PDU模式打包）
 */
public class Gsm7BitConverter {

    // 基本字符映射：字符 -> 7位编码值（0x00-0x7F）
    private static final Map<Character, Integer> BASIC_MAP = new HashMap<>();
    // 扩展字符映射：字符 -> 7位编码值（需要前置ESC 0x1B）
    private static final Map<Character, Integer> EXTENDED_MAP = new HashMap<>();
    // 转义字符（ESC）
    private static final int ESC = 0x1B;
    // 替换字符（问号）
    private static final int REPLACEMENT_CHAR = 0x3F;

    static {
        // 初始化基本字符映射（基于GSM 03.38默认字母表）
        // 索引 0x00-0x0F
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
        // 索引 0x10-0x1F（控制字符等，较少使用，但保留部分）
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
        // 索引 0x20-0x2F（常用标点和数字）
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
        // 索引 0x30-0x39（数字）
        for (int i = 0; i <= 9; i++) {
            BASIC_MAP.put((char) ('0' + i), 0x30 + i);
        }
        // 索引 0x3A-0x3F
        BASIC_MAP.put(':', 0x3A);
        BASIC_MAP.put(';', 0x3B);
        BASIC_MAP.put('<', 0x3C);
        BASIC_MAP.put('=', 0x3D);
        BASIC_MAP.put('>', 0x3E);
        BASIC_MAP.put('?', 0x3F);
        BASIC_MAP.put('¡', 0x40);
        // 大写字母 A-Z (0x41-0x5A)
        for (int i = 0; i < 26; i++) {
            BASIC_MAP.put((char) ('A' + i), 0x41 + i);
        }
        // 索引 0x5B-0x5F
        BASIC_MAP.put('Ä', 0x5B);
        BASIC_MAP.put('Å', 0x5C);
        BASIC_MAP.put('Æ', 0x5D);
        BASIC_MAP.put('Ç', 0x5E);
        BASIC_MAP.put('È', 0x5F);
        // 索引 0x60-0x7F
        BASIC_MAP.put('É', 0x60);
        BASIC_MAP.put('Ñ', 0x61);
        BASIC_MAP.put('Ö', 0x62);
        BASIC_MAP.put('Ü', 0x63);
        BASIC_MAP.put('§', 0x64);
        BASIC_MAP.put('¿', 0x65);
        // 小写字母 a-z (0x66-0x7F 但实际0x66-0x7F对应不同，需按标准)
        // 实际标准中小写字母映射到0x66-0x7F，但部分索引有特殊字符，简单起见连续映射常用小写
        for (int i = 0; i < 26; i++) {
            BASIC_MAP.put((char) ('a' + i), 0x66 + i);
        }
        // 补充部分遗漏的常见小写变体
        BASIC_MAP.put('ä', 0x7B);
        BASIC_MAP.put('ö', 0x7C);
        BASIC_MAP.put('ñ', 0x7D);
        BASIC_MAP.put('ü', 0x7E);
        BASIC_MAP.put('à', 0x7F);

        // 初始化扩展字符映射（需要前置 ESC 0x1B）
        EXTENDED_MAP.put('^', 0x14);
        EXTENDED_MAP.put('{', 0x28);
        EXTENDED_MAP.put('}', 0x29);
        EXTENDED_MAP.put('[', 0x3C);
        EXTENDED_MAP.put(']', 0x3E);
        EXTENDED_MAP.put('~', 0x3E);
        EXTENDED_MAP.put('\\', 0x2F);
        EXTENDED_MAP.put('|', 0x40);
        EXTENDED_MAP.put('€', 0x65);
    }

    /**
     * 将UTF-8字符串编码为GSM 7-bit字节数组（PDU打包模式）
     *
     * @param text 输入字符串（可包含中文等，不支持字符将替换为'?'）
     * @return GSM 7-bit编码后的字节数组，若输入为null或空则返回空数组
     */
    public static byte[] utf8ToGsm7Bit(String text) {
        if (text == null || text.isEmpty()) {
            return new byte[0];
        }

        // 步骤1: 将每个字符转换为7-bit值序列（扩展字符会产生两个值：ESC + 扩展码）
        List<Integer> septets = new ArrayList<>();
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);

            // GSM 7-bit字符集仅支持BMP字符
            if (codePoint > 0xFFFF) {
                septets.add(REPLACEMENT_CHAR);
                continue;
            }
            char ch = (char) codePoint;

            if (BASIC_MAP.containsKey(ch)) {
                septets.add(BASIC_MAP.get(ch));
            } else if (EXTENDED_MAP.containsKey(ch)) {
                septets.add(ESC);
                septets.add(EXTENDED_MAP.get(ch));
            } else {
                septets.add(REPLACEMENT_CHAR);
            }
        }

        // 步骤2: 将7-bit值序列打包为8-bit字节数组
        return listIntegerToByteArrayStream2(septets);
    }


    public static byte[] listIntegerToByteArrayStream2(List<Integer> list) {
        byte[] bytes = new byte[list.size()];
        IntStream.range(0, list.size())
                .forEach(i -> bytes[i] = (byte) (list.get(i) & 0xff));
        return bytes;
    }

    /**
     * 将7-bit值序列打包为8-bit字节数组（GSM PDU模式打包）
     *
     * @param septets 7-bit值列表（每个值范围0-127）
     * @return 打包后的字节数组
     */
    private static byte[] pack7bitToBytes(List<Integer> septets) {
        int septetCount = septets.size();
        if (septetCount == 0) {
            return new byte[0];
        }
        int byteCount = (septetCount * 7 + 7) / 8;
        byte[] result = new byte[byteCount];
        int shift = 0;
        int byteIndex = 0;

        for (int val : septets) {
            // 将当前7位值写入结果字节
            result[byteIndex] |= (val << shift);
            shift += 7;
            if (shift >= 8) {
                byteIndex++;
                if (byteIndex < byteCount) {
                    result[byteIndex] = (byte) (val >> (8 - (shift - 7)));
                }
                shift -= 8;
            }
        }
        return result;
    }

    // 测试示例
    public static void main(String[] args) {
        String test1 = "]";
        byte[] gsm1 = utf8ToGsm7Bit(test1);
        System.out.println(test1 + " -> " + bytesToHex(gsm1));

        String test2 = "!@#$%^&*{}[]";
        byte[] gsm2 = utf8ToGsm7Bit(test2);
        //21 00 23 02 25 1b 14 26 2a 1b 28 1b 29 1b 3c 1b 3e
        //21 00 23 02 25 1B 14 26 2A 1B 28 1B 29 1B 3C 1B 3E
        System.out.println(test2 + " -> " + bytesToHex(gsm2));
        //21 00 23 02 25 1b 14 262 a1 b3 c1 b3e
        String test3 = "!@#$%^&*[]";
        byte[] gsm3 = utf8ToGsm7Bit(test3);
        System.out.println(test3 + " -> " + bytesToHex(gsm3));
        //21 00 23 02 25 1B 14 26 2A 1B 3C 1B 3D
        //21 00 23 02 25 1b 14 26 2a 1b 3c 1b 3e
        String test4 = "!@#$%^&*[]";
        byte[] gsm4 = utf8ToGsm7Bit(test4);
        System.out.println(test4 + " -> " + bytesToHex(gsm4));

        //1b3e
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}