package com.texcellsms.sms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 捕获所有未处理的异常，返回统一格式
     */
    @ExceptionHandler(Exception.class)
    public Map<String, Object> handleException(Exception e) {
        // 异常日志
        logger.error("【系统全局异常】", e);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 500);
        result.put("msg", "System is busy, please try again later");
        return result;
    }
}