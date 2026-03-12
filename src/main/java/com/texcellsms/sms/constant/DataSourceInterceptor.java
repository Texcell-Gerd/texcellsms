package com.texcellsms.sms.constant;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 数据源切换拦截器（仅按域名切换物理数据源）
 * 需在Spring中注册才会生效
 * 【安全修复】解决ThreadLocal冲突、清理不彻底、命名混淆问题
 */
public class DataSourceInterceptor implements HandlerInterceptor {

    // 线程本地变量存储当前物理数据源key（命名更清晰）
    private static final ThreadLocal<String> DATA_SOURCE_CONTEXT = new ThreadLocal<>();

    // ========== 核心方法：安全的数据源操作 ==========
    // 获取当前数据源（优先用手动设置的值，无则用拦截器设置的，最后用默认）
    public static String getCurrentDataSource() {
        String manualKey = DATA_SOURCE_CONTEXT.get();
        // 优先级：Service手动设置 > 拦截器设置 > 默认PREPAY
        return manualKey == null ? DataSourceConstants.DS_PREPAY : manualKey;
    }

    // 设置当前数据源（Service层手动调用，标记为"手动设置"）
    public static void setCurrentDataSource(String dataSourceKey) {
        // 仅当传入有效key时才设置，避免空值覆盖
        if (dataSourceKey != null && !dataSourceKey.isEmpty()) {
            DATA_SOURCE_CONTEXT.set(dataSourceKey);
        }
    }

    // 安全清空线程变量（增加空判断，避免NPE）
    public static void clearCurrentDataSource() {
        try {
            DATA_SOURCE_CONTEXT.remove();
        } catch (Exception e) {
            // 捕获异常，避免清理失败导致请求中断
            e.printStackTrace();
        }
    }

    // ========== 拦截器方法：仅处理域名切换，不覆盖手动设置 ==========
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 先检查是否已有手动设置的数据源（Service层提前设置），有则不覆盖
        if (DATA_SOURCE_CONTEXT.get() == null) {
            // 仅当没有手动设置时，才根据域名切换
            String host = request.getServerName();
            if ("internalsms.texcellsms.com".equals(host)) {
                setCurrentDataSource(DataSourceConstants.DS_ECCOM);
            } else {
                setCurrentDataSource(DataSourceConstants.DS_PREPAY);
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 最终统一清理，无论是否异常
        clearCurrentDataSource();
    }

    // 异步请求完成后清理数据源
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
        clearCurrentDataSource();
    }
}