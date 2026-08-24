package com.texcellsms.sms.interceptor;

import com.texcellsms.sms.model.entity.IpRateLimitConfig;
import com.texcellsms.sms.service.IpRateLimitConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    // ====================== 默认配置 ======================
    private static final int DEFAULT_MAX_REQUESTS_PER_MINUTE = 60;
    private static final int DEFAULT_BLOCK_DURATION_MINUTES = 5;
    private static final long DEFAULT_BLOCK_DURATION_MS = DEFAULT_BLOCK_DURATION_MINUTES * 60 * 1000L;

    private static final int MAX_LOGIN_FAILS_PER_HOUR = 10;
    private static final Map<String, Long> blockedIps = new ConcurrentHashMap<>();

    // ====================== 数据库配置缓存 ======================
    private static final Map<String, IpRateLimitConfig> ipConfigCache = new ConcurrentHashMap<>();
    private static boolean configLoaded = false;
    private static long lastConfigLoadTime = 0;
    private static final long CONFIG_REFRESH_INTERVAL = 60000; // 60秒刷新一次

    // ====================== 安全检测规则 ======================
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

    private static final List<String> EXPORT_WHITE_LIST = Arrays.asList(
            "/api/eccom/submit-record/export/easyexcel",
            "/api/sms/queue/tasks/batch-delete"
    );

    @Autowired(required = false)
    private IpRateLimitConfigService configService;

    // ====================== 配置刷新方法 ======================

    /**
     * 刷新限流配置（从数据库加载）
     */
    public void refreshConfig() {
        if (configService == null) {
            logger.warn("IpRateLimitConfigService 未注入，使用默认配置");
            return;
        }

        try {
            List<IpRateLimitConfig> configs = configService.listEnabled();
            ipConfigCache.clear();
            for (IpRateLimitConfig config : configs) {
                if (config != null && config.getIp() != null) {
                    ipConfigCache.put(config.getIp(), config);
                }
            }
            configLoaded = true;
            lastConfigLoadTime = System.currentTimeMillis();
            logger.info("✅ IP限流配置已刷新，共加载 {} 条配置", ipConfigCache.size());
        } catch (Exception e) {
            logger.error("❌ 刷新IP限流配置失败，使用缓存配置", e);
        }
    }

    /**
     * 获取IP对应的限流配置（支持通配符匹配）
     */
    private IpRateLimitConfig getIpConfig(String ip) {
        // 检查是否需要刷新配置
        if (!configLoaded || (System.currentTimeMillis() - lastConfigLoadTime > CONFIG_REFRESH_INTERVAL)) {
            refreshConfig();
        }

        if (ip == null || ipConfigCache.isEmpty()) {
            return null;
        }

        // 1. 精确匹配（优先级最高）
        IpRateLimitConfig exactMatch = ipConfigCache.get(ip);
        if (exactMatch != null && exactMatch.getEnabled() == 1) {
            return exactMatch;
        }

        // 2. 通配符匹配（按优先级排序，priority高的优先）
        List<IpRateLimitConfig> wildcardMatches = new ArrayList<>();
        for (Map.Entry<String, IpRateLimitConfig> entry : ipConfigCache.entrySet()) {
            String pattern = entry.getKey();
            if (pattern != null && pattern.contains("*")) {
                // 将通配符转换为正则表达式
                String regex = pattern
                        .replace(".", "\\.")
                        .replace("*", ".*")
                        .replace("?", ".?");
                if (ip.matches(regex) && entry.getValue().getEnabled() == 1) {
                    wildcardMatches.add(entry.getValue());
                }
            }
        }

        // 按优先级排序（降序，数字大的优先）
        if (!wildcardMatches.isEmpty()) {
            wildcardMatches.sort((a, b) -> {
                int priorityA = a.getPriority() != null ? a.getPriority() : 0;
                int priorityB = b.getPriority() != null ? b.getPriority() : 0;
                return Integer.compare(priorityB, priorityA);
            });
            return wildcardMatches.get(0);
        }

        return null;
    }

    /**
     * 获取IP的限流配置值
     */
    private int getIpRateLimit(String ip) {
        IpRateLimitConfig config = getIpConfig(ip);
        if (config != null) {
            logger.debug("IP {} 使用自定义限流: {} 次/分钟", ip, config.getRateLimit());
            return config.getRateLimit();
        }
        return DEFAULT_MAX_REQUESTS_PER_MINUTE;
    }

    /**
     * 获取IP的封禁时长（分钟，用于日志显示）
     */
    private int getIpBlockDurationMinutes(String ip) {
        IpRateLimitConfig config = getIpConfig(ip);
        if (config != null) {
            return config.getBlockDuration();
        }
        return DEFAULT_BLOCK_DURATION_MINUTES;
    }

    // ====================== 公开的运维方法 ======================

    /**
     * 解封指定IP
     */
    public void unblockIp(String ip) {
        if (ip != null) {
            blockedIps.remove(ip);
            ipAccessRecords.remove(ip);
            logger.info("🔓 IP {} 已手动解封", ip);
        }
    }

    /**
     * 批量解封所有IP
     */
    public void unblockAllIps() {
        int blockedCount = blockedIps.size();
        blockedIps.clear();
        ipAccessRecords.clear();
        logger.info("🔓 所有IP已批量解封，共清除 {} 个封禁记录", blockedCount);
    }

    /**
     * 获取被封禁的IP列表
     */
    public Set<String> getBlockedIps() {
        return new HashSet<>(blockedIps.keySet());
    }

    /**
     * 获取IP的封禁时间
     */
    public Long getBlockTime(String ip) {
        return blockedIps.get(ip);
    }

    /**
     * 获取IP的封禁时长（毫秒）
     */
    public long getIpBlockDurationMs(String ip) {
        IpRateLimitConfig config = getIpConfig(ip);
        if (config != null) {
            return config.getBlockDuration() * 60 * 1000L;
        }
        return DEFAULT_BLOCK_DURATION_MS;
    }

    /**
     * 检查IP是否被封禁
     */
    public boolean isIpBlocked(String ip) {
        return isBlocked(ip);
    }

    /**
     * 获取默认限流值
     */
    public int getDefaultRateLimit() {
        return DEFAULT_MAX_REQUESTS_PER_MINUTE;
    }

    /**
     * 获取默认封禁时长（分钟）
     */
    public int getDefaultBlockDuration() {
        return DEFAULT_BLOCK_DURATION_MINUTES;
    }

    /**
     * 获取当前所有限流配置（用于管理界面）
     */
    public Map<String, Object> getCurrentConfigs() {
        Map<String, Object> result = new HashMap<>();
        result.put("defaultRateLimit", DEFAULT_MAX_REQUESTS_PER_MINUTE);
        result.put("defaultBlockDuration", DEFAULT_BLOCK_DURATION_MINUTES);
        result.put("configs", new HashMap<>(ipConfigCache));
        result.put("configLoaded", configLoaded);
        result.put("lastLoadTime", lastConfigLoadTime > 0 ? new Date(lastConfigLoadTime) : null);
        return result;
    }

    // ====================== 原有方法（修改部分） ======================

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

        // ====================== 检查IP是否被封禁 ======================
        if (isBlocked(clientIp)) {
            int blockMinutes = getIpBlockDurationMinutes(clientIp);
            recordAttack(clientIp, "IP临时封禁", uri, blockMinutes + "分钟内禁止访问");
            response.setStatus(429);
            response.setHeader("X-RateLimit-Reset", String.valueOf(blockMinutes * 60));
            return false;
        }

        // ====================== 检查限流 ======================
        if (checkRateLimit(clientIp)) {
            int limit = getIpRateLimit(clientIp);
            recordAttack(clientIp, "高频请求攻击", uri, "超过每分钟" + limit + "次");
            response.setStatus(429);
            response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
            return false;
        }

        // ====================== 安全检查 ======================
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

    /**
     * 检查IP是否被封禁（使用自定义封禁时长）
     */
    private boolean isBlocked(String ip) {
        Long blockTime = blockedIps.get(ip);
        if (blockTime != null) {
            long blockDuration = getIpBlockDurationMs(ip);
            if (System.currentTimeMillis() - blockTime < blockDuration) {
                return true;
            } else {
                blockedIps.remove(ip);
            }
        }
        return false;
    }

    /**
     * 封禁IP
     */
    private void blockIp(String ip) {
        blockedIps.put(ip, System.currentTimeMillis());
    }

    /**
     * 检查限流（使用自定义限流值）
     */
    private boolean checkRateLimit(String ip) {
        int maxRequests = getIpRateLimit(ip);

        long now = System.currentTimeMillis();
        LinkedList<Long> records = ipAccessRecords.computeIfAbsent(ip, k -> new LinkedList<>());
        synchronized (records) {
            // 清理1分钟前的记录
            while (!records.isEmpty() && records.getFirst() < now - 60000) {
                records.removeFirst();
            }
            records.add(now);

            // 判断是否超过限制
            if (records.size() > maxRequests) {
                blockIp(ip);
                logger.warn("🚫 IP {} 触发限流，限制 {} 次/分钟，实际 {} 次",
                        ip, maxRequests, records.size());
                return true;
            }
        }
        return false;
    }

    // ====================== 以下方法保持不变 ======================

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