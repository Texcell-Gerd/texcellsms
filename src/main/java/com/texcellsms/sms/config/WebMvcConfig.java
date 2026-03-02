package com.texcellsms.sms.config;

import com.texcellsms.sms.interceptor.AuthInterceptor;
import com.texcellsms.sms.interceptor.DynamicDataSourceInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1. 动态数据源拦截器（保留，不影响）
        registry.addInterceptor(new DynamicDataSourceInterceptor())
                .addPathPatterns("/api/**");

//         ========== 注释掉鉴权拦截器 ==========
//         2. 鉴权拦截器（注释后彻底不生效）
         registry.addInterceptor(new AuthInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns("/api/auth/login");
    }
}