package com.texcellsms.sms.interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
public class ApiLogInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(ApiLogInterceptor.class);

    // 使用 ThreadLocal 存储每个请求的开始时间
    private static final ThreadLocal<Long> startTimeThreadLocal = new ThreadLocal<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 记录请求开始时间
        startTimeThreadLocal.set(System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Long startTime = startTimeThreadLocal.get();
        if (startTime != null) {
            long cost = System.currentTimeMillis() - startTime;
            String method = request.getMethod();
            String path = request.getRequestURI();
            int status = response.getStatus();
            String statusText = status >= 200 && status < 300 ? "成功" : "失败(" + status + ")";

            // 只记录耗时超过1秒的慢请求，以及所有失败请求
            if (cost > 1000) {
                logger.warn("[API调用] {} {} 耗时:{}ms 状态:{} [慢请求]", method, path, cost, statusText);
            } else if (status >= 400) {
                logger.warn("[API调用] {} {} 耗时:{}ms 状态:{}", method, path, cost, statusText);
            } else {
                logger.info("[API调用] {} {} 耗时:{}ms 状态:{}", method, path, cost, statusText);
            }

            startTimeThreadLocal.remove();
        }
    }
}