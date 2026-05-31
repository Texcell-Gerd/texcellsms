package com.texcellsms.sms.interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Component
public class SecurityMonitorInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(SecurityMonitorInterceptor.class);
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private static final Map<String, LinkedList<Long>> ipAccessRecords = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> ipLoginFailCount = new ConcurrentHashMap<>();
    public static final List<String> recentAttacks = new LinkedList<>();
    public static final Map<String, AtomicInteger> attackStats = new ConcurrentHashMap<>();

    private static final int MAX_REQUESTS_PER_MINUTE = 60;
    private static final int MAX_LOGIN_FAILS_PER_HOUR = 10;
    private static final long BLOCK_DURATION = 5 * 60 * 1000;
    private static final Map<String, Long> blockedIps = new ConcurrentHashMap<>();

    private static final Pattern SQL_INJECTION_PATTERN = Pattern.compile(
            "(?i)(SELECT|INSERT|DELETE|DROP|UNION|ALTER|CREATE|TRUNCATE|EXEC|EXECUTE|" +
                    "'.*--|'.*#|/\\*.*\\*/|XP_|SP_|WAITFOR|DELAY|BENCHMARK|SLEEP\\()"
    );

    private static final Pattern XSS_PATTERN = Pattern.compile(
            "(?i)(<script|</script|alert\\(|onclick=|onload=|javascript:|<iframe|<img src|" +
                    "onerror=|prompt\\(|confirm\\(|document\\.|window\\.|location\\.|eval\\()"
    );

    private static final List<String> SENSITIVE_PATHS = Arrays.asList(
            "/admin", "/manage", "/config", "/env", "/actuator", "/swagger",
            "/druid", "/api/doc", "/v2/api-docs", "/metrics", "/heapdump",
            "/threaddump", "/logfile", "/h2-console", "/console"
    );

    private static final List<String> SCANNER_AGENTS = Arrays.asList(
            "nmap", "nikto", "sqlmap", "dirb", "gobuster", "masscan", "wpscan",
            "nessus", "openvas", "burp", "zap", "acunetix", "awvs"
    );

    // ====================== 接口白名单======================
    private static final List<String> EXPORT_WHITE_LIST = Arrays.asList(
            "/api/eccom/submit-record/export/easyexcel"
    );

    public static void recordLoginFailure(String ip) {
        if (ip == null) return;
        AtomicInteger failCount = ipLoginFailCount.computeIfAbsent(ip, k -> new AtomicInteger(0));
        int count = failCount.incrementAndGet();

        if (count >= MAX_LOGIN_FAILS_PER_HOUR) {
            String attackLog = String.format("[%s] | IP:%s | 类型:暴力破解 | 目标:/api/auth/login | 详情:登录失败%d次",
                    DATE_FORMAT.format(new Date()), ip, count);
            recordAttackDirect(attackLog);
            failCount.set(0);
        }
    }

    public static void recordAttackDirect(String attackLog) {
        synchronized (recentAttacks) {
            if (recentAttacks.size() >= 100) recentAttacks.remove(0);
            recentAttacks.add(attackLog);
        }

        String type = "未知攻击";
        if (attackLog.contains("暴力破解")) type = "暴力破解";
        else if (attackLog.contains("SQL注入")) type = "SQL注入攻击";
        else if (attackLog.contains("XSS")) type = "XSS攻击";
        else if (attackLog.contains("扫描")) type = "端口扫描";

        attackStats.computeIfAbsent(type, k -> new AtomicInteger()).incrementAndGet();
        logger.error("[安全告警] {}", attackLog);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String clientIp = getClientIp(request);
        String uri = request.getRequestURI();
        String method = request.getMethod();
        String userAgent = request.getHeader("User-Agent");
        String queryString = request.getQueryString();

        // ====================== ✅ 白名单直接放行 ======================
        if (EXPORT_WHITE_LIST.contains(uri)) {
            return true;
        }

        if (isBlocked(clientIp)) {
            recordAttack(clientIp, "IP临时封禁", uri, "5分钟内禁止访问");
            response.setStatus(429);
            return false;
        }

        if (checkRateLimit(clientIp)) {
            recordAttack(clientIp, "高频请求攻击", uri, "超过每分钟60次");
            response.setStatus(429);
            return false;
        }

        if (checkSqlInjection(uri, queryString, request)) {
            recordAttack(clientIp, "SQL注入攻击", uri, "检测到注入特征");
            response.setStatus(403);
            return false;
        }

        if (checkXss(uri, queryString, request)) {
            recordAttack(clientIp, "XSS攻击", uri, "检测到脚本特征");
            response.setStatus(403);
            return false;
        }

        if (checkSensitivePath(uri)) {
            recordAttack(clientIp, "敏感路径扫描", uri, "非法访问后台路径");
            response.setStatus(404);
            return false;
        }

        if (checkScanner(userAgent)) {
            recordAttack(clientIp, "扫描器攻击", uri, "检测到扫描器UA");
            response.setStatus(403);
            return false;
        }

        return true;
    }

    private void recordAttack(String ip, String type, String target, String detail) {
        String log = String.format("[%s] | IP:%s | 类型:%s | 目标:%s | 详情:%s",
                DATE_FORMAT.format(new Date()), ip, type, target, detail);
        recordAttackDirect(log);
    }

    private boolean isBlocked(String ip) {
        Long blockTime = blockedIps.get(ip);
        if (blockTime != null) {
            if (System.currentTimeMillis() - blockTime < BLOCK_DURATION) {
                return true;
            } else {
                blockedIps.remove(ip);
            }
        }
        return false;
    }

    private void blockIp(String ip) {
        blockedIps.put(ip, System.currentTimeMillis());
    }

    private boolean checkRateLimit(String ip) {
        long now = System.currentTimeMillis();
        LinkedList<Long> records = ipAccessRecords.computeIfAbsent(ip, k -> new LinkedList<>());
        synchronized (records) {
            while (!records.isEmpty() && records.getFirst() < now - 60000) {
                records.removeFirst();
            }
            records.add(now);
            if (records.size() > MAX_REQUESTS_PER_MINUTE) {
                blockIp(ip);
                return true;
            }
        }
        return false;
    }

    private boolean checkSqlInjection(String uri, String queryString, HttpServletRequest request) {
        List<String> whiteList = Arrays.asList("DELIVRD", "UNDELIV", "UNKNOWN", "submit", "fail", "success");

        if (SQL_INJECTION_PATTERN.matcher(uri).find()) {
            return true;
        }

        if (queryString != null) {
            for (String white : whiteList) {
                if (queryString.contains(white)) {
                    return false;
                }
            }
            if (SQL_INJECTION_PATTERN.matcher(queryString).find()) {
                return true;
            }
        }

        Map<String, String[]> params = request.getParameterMap();
        for (String[] values : params.values()) {
            for (String v : values) {
                if (v == null) continue;

                for (String white : whiteList) {
                    if (v.equalsIgnoreCase(white)) {
                        return false;
                    }
                }

                if (SQL_INJECTION_PATTERN.matcher(v).find()) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean checkXss(String uri, String queryString, HttpServletRequest request) {
        if (XSS_PATTERN.matcher(uri).find()) return true;
        if (queryString != null && XSS_PATTERN.matcher(queryString).find()) return true;
        Map<String, String[]> params = request.getParameterMap();
        for (String[] values : params.values()) {
            for (String v : values) {
                if (v != null && XSS_PATTERN.matcher(v).find()) return true;
            }
        }
        return false;
    }

    private boolean checkSensitivePath(String uri) {
        String lower = uri.toLowerCase();
        for (String s : SENSITIVE_PATHS) {
            if (lower.contains(s.toLowerCase())) return true;
        }
        return false;
    }

    private boolean checkScanner(String agent) {
        if (agent == null) return false;
        String lower = agent.toLowerCase();
        for (String s : SCANNER_AGENTS) {
            if (lower.contains(s)) return true;
        }
        return false;
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    public static Map<String, Object> getAttackStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalAttacks", recentAttacks.size());
        stats.put("attackTypes", new HashMap<>(attackStats));
        stats.put("recentAttacks", new ArrayList<>(recentAttacks));
        stats.put("blockedIps", new ArrayList<>(blockedIps.keySet()));
        return stats;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {}
}