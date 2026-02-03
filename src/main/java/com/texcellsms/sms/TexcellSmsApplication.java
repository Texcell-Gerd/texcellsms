package com.texcellsms.sms;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

// 核心：排除原生数据源自动配置
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@MapperScan("com.texcellsms.sms.mapper")
@EnableAspectJAutoProxy(exposeProxy = true)
public class TexcellSmsApplication {
    public static void main(String[] args) {
        SpringApplication.run(TexcellSmsApplication.class, args);
    }
}