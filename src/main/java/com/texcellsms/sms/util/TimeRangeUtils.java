package com.texcellsms.sms.util;

import cn.hutool.core.date.DateUtil;
import java.util.Date;

/**
 * 时间范围工具类
 */
public class TimeRangeUtils {

    /**
     * 获取当前时间往前推3天的时间字符串（和数据库TaskTime格式一致）
     */
    public static String getThreeDaysAgoTime() {
        // 获取当前时间
        Date now = new Date();
        // 往前推3天（72小时）
        Date threeDaysAgo = DateUtil.offsetHour(now, -72);
        // 转换为和数据库一致的字符串格式（根据你数据库的时间格式调整，比如 yyyy-MM-dd HH:mm:ss）
        return DateUtil.format(threeDaysAgo, "yyyy-MM-dd HH:mm:ss");
    }
}