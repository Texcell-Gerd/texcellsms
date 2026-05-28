package com.texcellsms.sms.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import com.alibaba.fastjson2.JSONObject;

/**
 * IP地理位置查询工具类
 * 使用免费API查询IP归属地
 */
public class IpLocationUtil {

    private static final Logger logger = LoggerFactory.getLogger(IpLocationUtil.class);

    // 备用API地址
    private static final String[] API_URLS = {
            "http://ip-api.com/json/%s?lang=zh-CN",
            "https://ipapi.co/%s/json/",
            "https://api.ip.sb/geoip/%s"
    };

    /**
     * 获取IP地理位置信息
     * @param ip IP地址
     * @return 地理位置信息Map
     */
    public static IpLocationInfo getLocation(String ip) {
        IpLocationInfo info = new IpLocationInfo();

        if (ip == null || ip.isEmpty() || ip.startsWith("127.") || ip.startsWith("192.168.") ||
                ip.startsWith("10.") || ip.startsWith("172.")) {
            info.setCountry("内网");
            info.setRegion("内网");
            info.setCity("内网");
            info.setIsp("内网");
            info.setLocation("内网IP");
            return info;
        }

        try {
            // 使用 ip-api.com 查询（免费，无需key）
            String urlStr = String.format(API_URLS[0], ip);
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            JSONObject json = JSONObject.parseObject(sb.toString());

            if ("success".equals(json.getString("status"))) {
                info.setCountry(json.getString("country"));
                info.setRegion(json.getString("regionName"));
                info.setCity(json.getString("city"));
                info.setIsp(json.getString("isp"));
                info.setLocation(String.format("%s %s %s",
                        json.getString("country"),
                        json.getString("regionName"),
                        json.getString("city")));
            } else {
                info.setLocation("未知位置");
            }

        } catch (Exception e) {
            logger.debug("IP地理位置查询失败: {} - {}", ip, e.getMessage());
            info.setLocation("查询失败");
        }

        return info;
    }

    /**
     * 地理位置信息内部类
     */
    public static class IpLocationInfo {
        private String country;
        private String region;
        private String city;
        private String isp;
        private String location;

        // Getters and Setters
        public String getCountry() { return country; }
        public void setCountry(String country) { this.country = country; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
        public String getIsp() { return isp; }
        public void setIsp(String isp) { this.isp = isp; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
    }
}