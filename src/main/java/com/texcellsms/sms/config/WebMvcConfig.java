package com.texcellsms.sms.config;

import com.texcellsms.sms.constant.DataSourceInterceptor; // 关键：导入新的拦截器
import com.texcellsms.sms.interceptor.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.Formatter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 关键：注册新的 DataSourceInterceptor（来自constant包）
        registry.addInterceptor(new DataSourceInterceptor())
                .addPathPatterns("/api/**");

        // 鉴权拦截器
         registry.addInterceptor(new AuthInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns("/api/auth/login");
    }
}