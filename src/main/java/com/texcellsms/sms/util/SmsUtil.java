package com.texcellsms.sms.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;

/**
 * 短信参数计算工具：生成TaskID + 计算DCS/SplitCount
 */
public class SmsUtil {

    /**
     * 生成TaskID（时间戳+6位随机数）
     */
    public static String generateTaskId() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmssSSS");
        String timestamp = sdf.format(new Date());
        int random = new Random().nextInt(999999);
        return timestamp + String.format("%06d", random);
    }

    /**
     * 计算DCS和SplitCount
     * @param content 短信内容
     * @return 包含DCS和SplitCount的元数据
     */
    public static SmsMeta calculateMeta(String content) {
        SmsMeta meta = new SmsMeta();
        if (content == null || content.isEmpty()) {
            meta.setDcs(0);
            meta.setSplitCount(1);
            return meta;
        }

        // 判断是否包含中文字符（Unicode > 0x7F）
        boolean hasChinese = content.chars().anyMatch(c -> c > 0x7F);
        meta.setDcs(hasChinese ? 8 : 0);

        // 计算拆分条数
        int contentLength = content.length();
        if (meta.getDcs() == 8) {
            // 中文：单条70字符，长短信每条67字符
            if (contentLength <= 70) {
                meta.setSplitCount(1);
            } else {
                meta.setSplitCount((contentLength + 66) / 67);
            }
        } else {
            // 英文：单条160字符，长短信每条153字符
            if (contentLength <= 160) {
                meta.setSplitCount(1);
            } else {
                meta.setSplitCount((contentLength + 152) / 153);
            }
        }
        return meta;
    }

    /**
     * 短信元数据（DCS + SplitCount）
     */
    public static class SmsMeta {
        private int dcs;
        private int splitCount;

        public int getDcs() {
            return dcs;
        }

        public void setDcs(int dcs) {
            this.dcs = dcs;
        }

        public int getSplitCount() {
            return splitCount;
        }

        public void setSplitCount(int splitCount) {
            this.splitCount = splitCount;
        }
    }

    public static void main(String[] args) {
        String content = generateTaskId();

        System.out.println(content);

    }
}