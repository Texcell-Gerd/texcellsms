package com.texcellsms.sms.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Component
public class ApiClientConfig {
    private static final Logger logger = LoggerFactory.getLogger(ApiClientConfig.class);
    private final Map<String, String> clientMap = new HashMap<>();

    public ApiClientConfig() {
        loadClientConfig();
    }

    /**
     * 加载配置文件中的客户信息
     */
    private void loadClientConfig() {
        try {
            ClassPathResource resource = new ClassPathResource("api-clients.txt");
            InputStream is = resource.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                // 跳过空行和注释（以#开头）
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    String username = parts[0].trim();
                    String password = parts[1].trim();
                    clientMap.put(username, password);
                    logger.info("加载客户配置: {} = {}", username, password);
                }
            }
            reader.close();
            logger.info("客户配置文件加载完成，共 {} 个客户", clientMap.size());
        } catch (Exception e) {
            logger.error("加载客户配置文件失败", e);
        }
    }

    /**
     * 根据用户名获取密码
     */
    public String getPasswordByUsername(String username) {
        return clientMap.get(username);
    }

    /**
     * 新增客户（运行时动态添加，可选）
     */
    public void addClient(String username, String password) {
        clientMap.put(username, password);
    }
}