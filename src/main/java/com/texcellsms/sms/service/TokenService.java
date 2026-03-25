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

@Component
@Service
public class TokenService {

    private final static long EXPIRE_TIME = Constants.TOKEN_EXPIRE * 60 * 1000L;
    protected static final long MILLIS_SECOND = 1000;
    private static final NCacheDBLink CacheDBLink = NCacheDBLink.getNCacheDBLink();

    // 获取当前登录用户名
    public static String getUsername() {
        String username = null;
        String token = getToken(ServletUtils.getRequest());
        if (StringUtils.isNotEmpty(token)) {
            Map<String, String> map = (Map<String, String>) ServletUtils.getSession().getServletContext().getAttribute(token);
            if (map != null) {
                username = map.get("Username");
            }
        }
        return username;
    }

    // 根据token获取用户名
    public static String getUsername(String Authorization) {
        if (StringUtils.isEmpty(Authorization)) return null;
        Map<String, String> map = (Map<String, String>) ServletUtils.getSession().getServletContext().getAttribute(Authorization);
        return map != null ? map.get("Username") : null;
    }

    // 从请求头获取token
    private static String getToken(HttpServletRequest request) {
        String token = request.getHeader(Constants.HEADER);
        if (token == null || token.trim().isEmpty()) {
            token = request.getHeader("authorization");
        }
        return token;
    }

    // 创建token并保存到servletContext（安全版）
    public void createToken(LoginUser loginUser) {
        HttpSession session = ServletUtils.getSession();
        Map<String, String> map = new HashMap<>();
        map.put("Username", loginUser.getUsername());
        map.put("IP", loginUser.getIP());
        map.put(Constants.HEADER, loginUser.getToken());
        map.put("createTime", String.valueOf(System.currentTimeMillis()));

        session.getServletContext().setAttribute(loginUser.getToken(), map);
        session.setMaxInactiveInterval(8 * 60 * 60);
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

    public static void deleteToken(String Authorization){
        if (StringUtils.isEmpty(Authorization)) return;
        ServletUtils.getSession().getServletContext().removeAttribute(Authorization);
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

    // ====================== 【重要】固定Token，永不刷新 ======================
    public static String refreshToken(String Authorization) {
        if (StringUtils.isEmpty(Authorization)) return null;

        // 只续期时间，不生成新Token！！！
        try {
            Map<String, String> map = (Map<String, String>) ServletUtils.getSession()
                    .getServletContext().getAttribute(Authorization);
            if (map != null) {
                map.put("createTime", String.valueOf(System.currentTimeMillis()));
                ServletUtils.getSession().getServletContext().setAttribute(Authorization, map);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return Authorization;
    }
}