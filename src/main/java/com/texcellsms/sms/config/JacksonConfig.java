package com.texcellsms.sms.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.DateSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/**
 * 全局Jackson配置：Date序列化为香港时间（东8区）
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();

        // 1. 配置香港时间格式（东8区）
        SimpleDateFormat hkFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        hkFormat.setTimeZone(TimeZone.getTimeZone("Asia/Hong_Kong")); // 香港时区

        // 2. 全局Date序列化规则
        objectMapper.setDateFormat(hkFormat);
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // 3. 注册序列化器
        SimpleModule module = new SimpleModule();
        module.addSerializer(Date.class, new DateSerializer(false, hkFormat));
        objectMapper.registerModule(module);

        return objectMapper;
    }
}