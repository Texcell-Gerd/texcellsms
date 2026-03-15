package com.texcellsms.sms.service;

import CacheDB.NCacheDBLink;
import com.texcellsms.sms.model.vo.CacheDBLonginResult;
import com.texcellsms.sms.model.vo.LoginUser;
import com.texcellsms.sms.util.Constants;
import com.texcellsms.sms.util.ServletUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
@Service
public class TokenService {

    private final static long EXPIRE_TIME = Constants.TOKEN_EXPIRE * 60;
    protected static final long MILLIS_SECOND = 1000;
    private static final NCacheDBLink CacheDBLink = NCacheDBLink.getNCacheDBLink();

    // 获取当前登录用户名
    public static String getUsername() {
        String username = null;
        String token = getToken(ServletUtils.getRequest());
        if (StringUtils.isNotEmpty(token)) {
            Map<String, String> map = (Map<String, String>) ServletUtils.getSession().getServletContext().getAttribute(token);
            username = map.get("Username");
        }
        return username;
    }

    // 根据token获取用户名
    public static String getUsername(String Authorization) {
        String username = null;
        if (StringUtils.isNotEmpty(Authorization)) {
            Map<String, String> map = (Map<String, String>) ServletUtils.getSession().getServletContext().getAttribute(Authorization);
            username = map.get("Username");
        }
        return username;
    }

    // 从请求头获取token
    private static String getToken(HttpServletRequest request) {
        return request.getHeader(Constants.HEADER);
    }

    // 创建token并保存到servletContext
    public void createToken(LoginUser loginUser) {
        HttpSession session = ServletUtils.getSession();
        Map<String, String> map = new HashMap<>();
        map.put("Username", loginUser.getUsername());
        map.put("IP", loginUser.getIP());
        map.put(Constants.HEADER, loginUser.getToken());
        session.getServletContext().setAttribute(loginUser.getToken(), map);
        session.setMaxInactiveInterval(1000 * 60 * 60);
    }

    // 登录验证（CacheDB）
    public synchronized CacheDBLonginResult getToken(LoginUser loginUser) {
        CacheDBLink.InitEnv();
        CacheDBLink.ConnectToCacheDB();
        Map<String, Object> map = CacheDBLink.ExecProc_QueryGWUserPass_With2FA(
                loginUser.getUsername(),
                loginUser.getPassword(),
                loginUser.getIP()
        );
        String Result = (String) map.get("Result");
        String WhitelistIP = (String) map.get("WhitelistIP");
        String FAKey = (String) map.get("FAKey");
        String IsEnable = (String) map.get("IsEnable");
        CacheDBLonginResult result = new CacheDBLonginResult(Result, WhitelistIP, FAKey, IsEnable);
        CacheDBLink.Disconnect();
        CacheDBLink.Destroy();
        return result;
    }

    // 获取真实IP
    public static String getIpAddress(HttpServletRequest request) {
        String ip = request.getHeader("x-forwarded-for");
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.length() != 0) {
            String[] ips = ip.split(",");
            for (String strIp : ips) {
                if (!"unknown".equalsIgnoreCase(strIp)) {
                    ip = strIp;
                    break;
                }
            }
        }
        return ip;
    }

    // 刷新token（核心）
    public static String refreshToken(String Authorization) {
        if (Authorization == null || Authorization.isEmpty()) {
            return null;
        }
        Map<String, String> map = (Map<String, String>) ServletUtils.getSession().getServletContext().getAttribute(Authorization);
        String newToken = UUID.randomUUID().toString().replace("-", "") + Authorization.substring(Math.min(Authorization.length(), 32));
        map.put(Constants.HEADER, newToken);

        HttpSession session = ServletUtils.getSession();
        new Thread(() -> {
            try {
                Thread.sleep(30000);
            } catch (Exception ignored) {}
            session.getServletContext().removeAttribute(Authorization);
        }).start();

        session.getServletContext().setAttribute(newToken, map);
        return newToken;
    }
}