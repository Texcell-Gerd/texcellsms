package com.texcellsms.sms.config;

import com.texcellsms.sms.interceptor.AuthInterceptor;
import com.texcellsms.sms.interceptor.DynamicDataSourceInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web全局配置：注册拦截器
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1. 动态数据源拦截器（优先级更高）
        registry.addInterceptor(new DynamicDataSourceInterceptor())
                .addPathPatterns("/api/**"); // 只拦截/api开头的接口

        // 2. 鉴权拦截器
        registry.addInterceptor(new AuthInterceptor())
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login"); // 排除登录接口
    }
}