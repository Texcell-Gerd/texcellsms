package com.texcellsms.sms.interceptor;


import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 简化版：只切换物理数据源（eccom/prepay），库名由SQL指定
 * 新增兼容方法：setCurrentDataSource/clearCurrentDataSource
 */
public class DynamicDataSourceInterceptor implements HandlerInterceptor {

    // 物理数据源常量（仅2个）
    public static final String DS_ECCOM = "eccom";
    public static final String DS_PREPAY = "prepay";

    // 线程本地变量存储当前物理数据源key
    private static final ThreadLocal<String> DATASOURCE_KEY = new ThreadLocal<>();

    // ========== 原有方法 ==========
    // 获取当前物理数据源
    public static String getCurrentDataSource() {
        return DATASOURCE_KEY.get();
    }

    // 清空线程变量
    public static void clearDataSource() {
        DATASOURCE_KEY.remove();
    }

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

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 根据域名切换物理数据源
        String host = request.getServerName();
        if ("internalsms.texcellsms.com".equals(host)) {
            DATASOURCE_KEY.set(DS_ECCOM); // 切换到eccom物理数据源
        } else {
            // Bulksms.texcellsms.com/localhost → 切换到prepay物理数据源
            DATASOURCE_KEY.set(DS_PREPAY);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        clearDataSource();
    }
}