package com.texcellsms.sms.util;

import com.texcellsms.sms.mapper.webportal.CustomerApiClientMapper;
import com.texcellsms.sms.model.entity.CustomerApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ApiClientConfig {
    private static final Logger logger = LoggerFactory.getLogger(ApiClientConfig.class);
    private final Map<String, String> clientMap = new HashMap<>();

    @Autowired
    private CustomerApiClientMapper customerApiClientMapper;

    @PostConstruct
    public void init() {
        loadFromDatabase();
    }

    /**
     * 从数据库加载客户配置
     */
    private void loadFromDatabase() {
        try {
            List<CustomerApiClient> clients = customerApiClientMapper.selectAllActive();
            clientMap.clear();
            for (CustomerApiClient client : clients) {
                if (client.getStatus() == 1) {
                    clientMap.put(client.getUsername(), client.getPassword());
                }
            }
            logger.info("从数据库加载客户配置完成，共 {} 个客户", clientMap.size());
        } catch (Exception e) {
            logger.error("从数据库加载客户配置失败", e);
        }
    }

    /**
     * 根据用户名获取密码
     */
    public String getPasswordByUsername(String username) {
        return clientMap.get(username);
    }

    /**
     * 刷新配置（重新从数据库加载）
     */
    public void refresh() {
        loadFromDatabase();
        logger.info("客户配置已刷新，当前共 {} 个客户", clientMap.size());
    }
}