package com.texcellsms.sms.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;

// 生成唯一ID工具（PreTaskId格式一致）
public class IdGenerator {
    public static String generatePreTaskId() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmssSSS");
        String timestamp = sdf.format(new Date());
        // 生成随机后缀（和你示例格式匹配）
        String suffix = String.format("%06d", new Random().nextInt(999999));
        return timestamp + "00" + suffix;
    }
}
