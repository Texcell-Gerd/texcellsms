package com.texcellsms.sms.interceptor;

import com.texcellsms.sms.constant.DataSourceConstants;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;



/**
 * 动态数据源拦截器：根据请求域名切换数据源
 */
public class DynamicDataSourceInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 1. 获取请求域名（本地调试可手动模拟，如request.getParameter("host")）
        String host = request.getServerName();
        // 2. 根据域名切换数据源
        if ("Global.texcellsms.com".equals(host)) {
            DynamicDataSourceContextHolder.push(DataSourceConstants.DS_ECCOM);
        } else if ("Bulksms.texcellsms.com".equals(host)) {
            DynamicDataSourceContextHolder.push(DataSourceConstants.DS_PREPAY);
        }
        // 本地调试默认使用eccom
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 清空数据源上下文，避免线程污染
        DynamicDataSourceContextHolder.clear();
    }
}