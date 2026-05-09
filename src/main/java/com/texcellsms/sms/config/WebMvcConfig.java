package com.texcellsms.sms.config;

import com.texcellsms.sms.constant.DataSourceInterceptor;
import com.texcellsms.sms.interceptor.AuthInterceptor;
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

        // 数据源切换拦截器
        registry.addInterceptor(new DataSourceInterceptor())
                .addPathPatterns("/api/**", "/open/api/**");

        // 鉴权拦截器
        registry.addInterceptor(new AuthInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/monitor",
                        "/api/monitor/logs",
                        "/open/api/sms/send",
                        "/open/api/sms/receipt",
                        "/open/api/sms/balance",
                        "/open/api/sms/task/detail"
                );
    }
}