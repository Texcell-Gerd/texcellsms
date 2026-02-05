package com.texcellsms.sms.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 简化版Knife4j配置（适配Spring Boot 4.x）
 */
@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("TexcellSMS 短信服务接口文档")
                        .description("包含登录、多数据源查询、账单管理等核心接口")
                        .version("1.0.0"));
    }
}