package com.texcellsms.sms.config;

import com.texcellsms.sms.constant.DataSourceInterceptor;
import com.texcellsms.sms.interceptor.ApiLogInterceptor;
import com.texcellsms.sms.interceptor.AuthInterceptor;
import com.texcellsms.sms.interceptor.SecurityMonitorInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private ApiLogInterceptor apiLogInterceptor;

    @Autowired
    private SecurityMonitorInterceptor securityMonitorInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        // 安全监控拦截器
        registry.addInterceptor(securityMonitorInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/monitor/**",
                        "/api/monitor/**"
                );

        // API日志拦截器
        registry.addInterceptor(apiLogInterceptor)
                .addPathPatterns("/api/**", "/open/api/**")
                .excludePathPatterns("/api/monitor/**", "/monitor/**");

        // 数据源切换拦截器
        registry.addInterceptor(new DataSourceInterceptor())
                .addPathPatterns("/api/**", "/open/api/**");

        // 鉴权拦截器
        registry.addInterceptor(new AuthInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/monitor/**",
                        "/api/monitor/**",
                        "/open/api/sms/send",
                        "/open/api/sms/receipt",
                        "/open/api/sms/balance",
                        "/open/api/sms/task/detail"
                );
    }
}