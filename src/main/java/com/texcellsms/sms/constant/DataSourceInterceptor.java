package com.texcellsms.sms.constant;

import com.texcellsms.sms.config.DynamicDataSourceConfig;
import org.springframework.web.servlet.HandlerInterceptor;
/**
 * 数据源切换拦截器（仅按域名切换物理数据源）
 * 需在Spring中注册才会生效
 * 【安全修复】解决ThreadLocal冲突、清理不彻底、命名混淆问题
 */
public class DataSourceInterceptor implements HandlerInterceptor {

    // 线程本地变量存储当前物理数据源key（命名更清晰）
    private static final ThreadLocal<String> DATA_SOURCE_CONTEXT = new ThreadLocal<>();

    // 设置当前数据源（Service层手动调用，标记为"手动设置"）
    public static void setCurrentDataSource(String dataSourceKey) {
        // 仅当传入有效key时才设置，避免空值覆盖
        if (dataSourceKey != null && !dataSourceKey.isEmpty()) {
            DATA_SOURCE_CONTEXT.set(dataSourceKey);
        }
    }
}