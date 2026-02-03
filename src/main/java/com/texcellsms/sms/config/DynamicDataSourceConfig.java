package com.texcellsms.sms.config;


import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.context.annotation.Configuration;

/**
 * 动态数据源配置：Spring Boot 4.x + dynamic-datasource 4.x 无需手动构建数据源
 */
@Configuration
@AutoConfigureAfter(DynamicDataSourceAutoConfiguration.class)
public class DynamicDataSourceConfig {
    // 无需手动定义DataSource Bean，dynamic-datasource会自动根据application.yml配置
}