package com.texcellsms.sms.config;

import com.texcellsms.sms.constant.DataSourceInterceptor;
import com.texcellsms.sms.interceptor.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        // 数据源切换拦截器
        registry.addInterceptor(new DataSourceInterceptor())
                .addPathPatterns("/api/**");

        // 鉴权拦截器（放行监控页面）
        registry.addInterceptor(new AuthInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/monitor",        // 放行监控页面
                        "/api/monitor/logs" // 放行监控接口
                );
    }
}