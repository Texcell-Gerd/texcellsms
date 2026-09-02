package com.texcellsms.sms.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class WeChatRobotUtil {

    private static final Logger logger = LoggerFactory.getLogger(WeChatRobotUtil.class);

    @Value("${wechat.robot.webhook.url:}")
    private String webhookUrl;

    @Value("${wechat.robot.enabled:false}")
    private boolean enabled;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 发送 Markdown 类型消息（使用默认 Webhook）
     */
    public boolean sendMarkdownMessage(String content) {
        return sendMarkdownMessage(content, null);
    }

    /**
     * 发送 Markdown 类型消息（支持自定义 Webhook）
     */
    public boolean sendMarkdownMessage(String content, String webhookUrl) {
        if (!enabled) {
            logger.info("企业微信机器人未启用，消息内容：\n{}", content);
            return false;
        }

        String targetUrl = (webhookUrl != null && !webhookUrl.trim().isEmpty())
                ? webhookUrl
                : this.webhookUrl;

        if (targetUrl == null || targetUrl.trim().isEmpty()) {
            logger.error("企业微信机器人 Webhook URL 未配置");
            return false;
        }

        try {
            Map<String, Object> message = new HashMap<>();
            message.put("msgtype", "markdown");

            Map<String, String> markdown = new HashMap<>();
            markdown.put("content", content);
            message.put("markdown", markdown);

            return sendMessageToUrl(message, targetUrl);
        } catch (Exception e) {
            logger.error("发送企业微信消息失败", e);
            return false;
        }
    }

    /**
     * 发送文本类型消息
     */
    public boolean sendTextMessage(String content, List<String> mentionedList, boolean mentionAll) {
        if (!enabled) {
            logger.info("企业微信机器人未启用，消息内容：\n{}", content);
            return false;
        }

        if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
            logger.error("企业微信机器人 Webhook URL 未配置");
            return false;
        }

        try {
            Map<String, Object> message = new HashMap<>();
            message.put("msgtype", "text");

            Map<String, Object> text = new HashMap<>();
            text.put("content", content);

            if (mentionAll) {
                text.put("mentioned_list", Arrays.asList("@all"));
            } else if (mentionedList != null && !mentionedList.isEmpty()) {
                text.put("mentioned_list", mentionedList);
            }

            message.put("text", text);

            return sendMessageToUrl(message, webhookUrl);
        } catch (Exception e) {
            logger.error("发送企业微信消息失败", e);
            return false;
        }
    }

    /**
     * 发送消息到指定 URL
     */
    private boolean sendMessageToUrl(Map<String, Object> message, String urlStr) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlStr);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            String json = objectMapper.writeValueAsString(message);
            logger.debug("发送企业微信消息: {}", json);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = json.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                logger.info("企业微信消息发送成功");
                return true;
            } else {
                logger.error("企业微信消息发送失败，响应码: {}", responseCode);
                return false;
            }
        } catch (Exception e) {
            logger.error("企业微信消息发送异常", e);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}