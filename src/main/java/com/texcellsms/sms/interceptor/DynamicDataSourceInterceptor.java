package com.texcellsms.sms.interceptor;



import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 新增兼容方法：setCurrentDataSource/clearCurrentDataSource
 */
public class DynamicDataSourceInterceptor implements HandlerInterceptor {

    // 线程本地变量存储当前物理数据源key
    private static final ThreadLocal<String> DATASOURCE_KEY = new ThreadLocal<>();

    // ========== 新增兼容方法（解决Service调用报错） ==========
    /**
     * 设置当前数据源（Service层调用）
     */
    public static void setCurrentDataSource(String dataSourceKey) {
        DATASOURCE_KEY.set(dataSourceKey);
    }

    /**
     * 清空当前数据源（Service层调用）
     */
    public static void clearCurrentDataSource() {
        DATASOURCE_KEY.remove();
    }
}