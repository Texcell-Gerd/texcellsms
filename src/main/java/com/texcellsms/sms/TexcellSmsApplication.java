package com.texcellsms.sms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 移除所有排除/扫描注解，框架自动处理
@SpringBootApplication
public class TexcellSmsApplication {
    public static void main(String[] args) {
        SpringApplication.run(TexcellSmsApplication.class, args);
    }
}