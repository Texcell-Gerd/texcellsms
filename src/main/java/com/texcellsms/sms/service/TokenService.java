package com.texcellsms.sms.service;

import CacheDB.NCacheDBLink;
import com.alibaba.fastjson2.JSON;
import com.texcellsms.sms.model.vo.CacheDBLonginResult;
import com.texcellsms.sms.model.vo.LoginUser;
import com.texcellsms.sms.util.Constants;
import com.texcellsms.sms.util.ServletUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

@Service
public class TokenService {

    private static final long EXPIRE_TIME = Constants.TOKEN_EXPIRE * 60 * 1000L;
    protected static final long MILLIS_SECOND = 1000;
    private static final NCacheDBLink CACHE_DB_LINK = NCacheDBLink.getNCacheDBLink();
    private static final Logger logger = LoggerFactory.getLogger(TokenService.class);
    /**
     * 获取当前登录用户名
     */
    public static String getUsername() {
        HttpServletRequest request = ServletUtils.getRequest();
        if (request == null) {
            return null;
        }
        String token = getToken(request);
        if (StringUtils.isEmpty(token)) {
            return null;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Map<String, String> map = (Map<String, String>) session.getServletContext().getAttribute(token);
        return map != null ? map.get("Username") : null;
    }

    /**
     * 根据token获取用户名
     */
    public static String getUsername(String authorization) {
        if (StringUtils.isEmpty(authorization)) {
            return null;
        }
        HttpServletRequest request = ServletUtils.getRequest();
        if (request == null) {
            return null;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Map<String, String> map = (Map<String, String>) session.getServletContext().getAttribute(authorization);
        return map != null ? map.get("Username") : null;
    }

    /**
     * 从请求头获取token
     */
    private static String getToken(HttpServletRequest request) {
        String token = request.getHeader(Constants.HEADER);
        if (StringUtils.isEmpty(token)) {
            token = request.getHeader("authorization");
        }
        return token;
    }

    /**
     * 创建token并保存到servletContext
     */
    public void createToken(LoginUser loginUser) {
        if (loginUser == null || StringUtils.isEmpty(loginUser.getToken())) {
            return;
        }
        HttpSession session = ServletUtils.getSession();
        if (session == null) {
            return;
        }
        Map<String, String> map = new HashMap<>();
        map.put("Username", loginUser.getUsername());
        map.put("IP", loginUser.getIP());
        map.put(Constants.HEADER, loginUser.getToken());
        map.put("createTime", String.valueOf(System.currentTimeMillis()));

        session.getServletContext().setAttribute(loginUser.getToken(), map);
        session.setMaxInactiveInterval(8 * 60 * 60);
    }

    /**
     * 登录验证（CacheDB）
     */
    public synchronized CacheDBLonginResult getToken(LoginUser loginUser) {
        if (loginUser == null) {
            return new CacheDBLonginResult("-1", "", "", "0","");
        }
        CACHE_DB_LINK.InitEnv();
        CACHE_DB_LINK.ConnectToCacheDB();
        Map<String, Object> map = CACHE_DB_LINK.ExecProc_QueryGWUserPass_With2FA(
                loginUser.getUsername(),
                loginUser.getPassword(),
                loginUser.getIP()
        );
        String result = (String) map.getOrDefault("Result", "-1");
        String whitelistIP = (String) map.getOrDefault("WhitelistIP", "");
        String faKey = (String) map.getOrDefault("FAKey", "");
        String isEnable = (String) map.getOrDefault("IsEnable", "0");
        String  Email = (String) map.getOrDefault("Email", "");

        CacheDBLonginResult loginResult = new CacheDBLonginResult(result, whitelistIP, faKey, isEnable,Email);
        CACHE_DB_LINK.Disconnect();
        CACHE_DB_LINK.Destroy();
        return loginResult;
    }

    /**
     * 删除token
     */
    public static void deleteToken(String authorization) {
        if (StringUtils.isEmpty(authorization)) {
            return;
        }
        HttpServletRequest request = ServletUtils.getRequest();
        if (request == null) {
            return;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        session.getServletContext().removeAttribute(authorization);
    }

    /**
     * 获取用户真实IP
     */
    public static String getIpAddress(HttpServletRequest request) {
        if (request == null) {
            logger.warn("request为空");
            return "";
        }

        String cfIp = request.getHeader("CF-Connecting-IP");
        String realIp = request.getHeader("X-Real-IP");
        String xff = request.getHeader("X-Forwarded-For");
        String remoteAddr = request.getRemoteAddr();
        String host = request.getHeader("host");

        logger.info("【域名】{} | CF-IP:{} | X-Real-IP:{} | XFF:{} | Remote:{}",
                host, cfIp, realIp, xff, remoteAddr);

        // 1️⃣ 优先：CF-Connecting-IP（Cloudflare 专用）
        if (isValidIp(cfIp)) {
            String real = cfIp.trim();
            logger.info("✅ 最终登录IP(Cloudflare)：{}", real);
            return real;
        }

        // 2️⃣ 次选：X-Real-IP（Nginx 透传）
        if (isValidIp(realIp)) {
            String real = realIp.trim();
            logger.info("✅ 最终登录IP(Nginx直连)：{}", real);
            return real;
        }

        // 3️⃣ 备用：X-Forwarded-For（取第一个有效IP）
        if (isValidIp(xff)) {
            String[] ips = xff.split(",");
            for (String strIp : ips) {
                if (isValidIp(strIp)) {
                    String real = strIp.trim();
                    logger.info("✅ 最终登录IP(X-Forwarded-For)：{}", real);
                    return real;
                }
            }
        }

        // 4️⃣ 兜底
        String ip = remoteAddr;
        logger.warn("⚠️ 兜底使用 RemoteAddr：{}", ip);
        return isValidIp(ip) ? ip.trim() : "";
    }

    private static boolean isValidIp(String ip) {
        if (ip == null) return false;
        String trimmed = ip.trim();
        if (trimmed.isEmpty()) return false;
        if ("unknown".equalsIgnoreCase(trimmed)) return false;
        return true;
    }

    /**
     * 固定Token，永不刷新，只续期时间
     */
    public static String refreshToken(String authorization) {
        if (StringUtils.isEmpty(authorization)) {
            return null;
        }
        try {
            HttpServletRequest request = ServletUtils.getRequest();
            if (request == null) {
                return authorization;
            }
            HttpSession session = request.getSession(false);
            if (session == null) {
                return authorization;
            }
            Map<String, String> map = (Map<String, String>) session.getServletContext().getAttribute(authorization);
            if (map != null) {
                map.put("createTime", String.valueOf(System.currentTimeMillis()));
                session.getServletContext().setAttribute(authorization, map);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return authorization;
    }
}