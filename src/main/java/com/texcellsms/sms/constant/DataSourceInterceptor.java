package com.texcellsms.sms.constant;

import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 数据源切换拦截器（仅按域名切换物理数据源）
 * 需在Spring中注册才会生效
 */
public class DataSourceInterceptor implements HandlerInterceptor {

    // 线程本地变量存储当前物理数据源key（和DynamicDataSourceInterceptor的变量名区分）
    private static final ThreadLocal<String> CURRENT_DATASOURCE = new ThreadLocal<>();

    // 获取当前数据源
    public static String getCurrentDataSource() {
        String key = CURRENT_DATASOURCE.get(); // 直接获取ThreadLocal值
        return key == null ? DataSourceConstants.DS_PREPAY : key;
    }

    // 设置当前数据源（Service层调用）
    public static void setCurrentDataSource(String dataSourceKey) {
        CURRENT_DATASOURCE.set(dataSourceKey);
    }

    // 清空线程变量
    public static void clearCurrentDataSource() {
        CURRENT_DATASOURCE.remove();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 根据域名切换物理数据源
        String host = request.getServerName();
        if ("internalsms.texcellsms.com".equals(host)) {
            setCurrentDataSource(DataSourceConstants.DS_ECCOM);
        } else {
            setCurrentDataSource(DataSourceConstants.DS_PREPAY);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        clearCurrentDataSource();
    }
}