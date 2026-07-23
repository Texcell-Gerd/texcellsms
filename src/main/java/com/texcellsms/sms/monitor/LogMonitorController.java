package com.texcellsms.sms.monitor;


import CacheDB.NCacheDBLink;
import com.texcellsms.sms.interceptor.SecurityMonitorInterceptor;
import com.texcellsms.sms.mapper.webportal.*;
import com.texcellsms.sms.model.entity.*;
import com.texcellsms.sms.service.SmsQueueService;
import com.texcellsms.sms.util.ApiClientConfig;
import com.texcellsms.sms.util.WeChatRobotUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
public class LogMonitorController {

    private static final Logger logger = LoggerFactory.getLogger(LogMonitorController.class);
    private static final String MONITOR_SECRET = "dn17ORUwf36Znl";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");


    // 操作类型常量（在类顶部添加）
    private static final String OPS_RESET_2FA = "RESET_2FA";
    private static final String OPS_DISABLE_2FA = "DISABLE_2FA";
    private static final String OPS_UNLOCK_ACCOUNT = "UNLOCK_ACCOUNT";
    private static final String OPS_UNLOCK_IP = "UNLOCK_IP";

//    private static final String ADMIN_USERNAME = "Administrator";
//    private static final String ADMIN_PASSWORD = "dn17ORUwf36Znl";

    @Autowired
    private LoginAuditLogMapper loginAuditLogMapper;

    @Autowired
    private LogMonitorTask logMonitorTask;

    @Autowired
    private CustomerNumberFileMapper customerNumberFileMapper;

    @Autowired
    private CustomerApiClientMapper customerApiClientMapper;

    @Autowired
    private MonitorUserMapper monitorUserMapper;

    @Autowired
    private OpsAuditLogMapper opsAuditLogMapper;

    @Autowired
    private SmsQueueService smsQueueService;

    @Autowired
    ApiClientConfig apiClientConfig;

    @Autowired
    private QueueMonitorLogMapper queueMonitorLogMapper;

    @Autowired
    private SmsQueueMapper smsQueueMapper;

    @Autowired
    private WeChatRobotUtil weChatRobotUtil;


    private String getRealIp(HttpServletRequest request) {
        String ip = request.getHeader("CF-Connecting-IP");  // Cloudflare
        if (isValidIp(ip)) return ip;

        ip = request.getHeader("X-Real-IP");  // Nginx透传
        if (isValidIp(ip)) return ip;

        ip = request.getHeader("X-Forwarded-For");
        if (isValidIp(ip)) {
            ip = ip.split(",")[0].trim();
            if (isValidIp(ip)) return ip;
        }

        ip = request.getHeader("Proxy-Client-IP");
        if (isValidIp(ip)) return ip;

        ip = request.getHeader("WL-Proxy-Client-IP");
        if (isValidIp(ip)) return ip;

        ip = request.getRemoteAddr();
        return isValidIp(ip) ? ip : "unknown";
    }

    private boolean isValidIp(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        if ("unknown".equalsIgnoreCase(ip)) return false;
        return true;
    }

    /**
     * IP权限检查 - 只允许数据库白名单中的IP访问
     * @param ip 客户端IP
     * @param username 登录用户名（未登录时为null）
     * @return true=允许访问，false=拒绝访问
     */
    private boolean isIpAllowed(String ip, String username) {
        if (ip == null || ip.isEmpty()) {
            logger.warn("IP为空，拒绝访问");
            return false;
        }
        if ("unknown".equalsIgnoreCase(ip)) {
            logger.warn("IP为unknown，拒绝访问");
            return false;
        }

        // 未登录时，检查是否有任何已启用的用户配置了该IP为白名单
        if (username == null || username.isEmpty()) {
            return isIpInAnyUserWhitelist(ip);
        }

        // 已登录，检查该用户的IP白名单
        MonitorUser user = monitorUserMapper.selectByUsername(username);
        if (user == null) {
            logger.warn("用户 {} 不存在，拒绝IP {} 访问", username, ip);
            return false;
        }

        // 检查用户状态
        if (user.getStatus() != 1) {
            logger.warn("用户 {} 已被禁用，拒绝IP {} 访问", username, ip);
            return false;
        }

        String allowedIpsStr = user.getAllowedIps();
        if (allowedIpsStr == null || allowedIpsStr.trim().isEmpty()) {
            logger.warn("用户 {} 未配置IP白名单，拒绝IP {} 访问", username, ip);
            return false;
        }

        // 解析白名单并匹配
        String[] allowedIps = allowedIpsStr.split(",");
        for (String allowedIp : allowedIps) {
            allowedIp = allowedIp.trim();
            if (allowedIp.isEmpty()) continue;

            if (isIpMatch(ip, allowedIp)) {
                logger.info("IP {} 匹配白名单规则 {}，允许用户 {} 访问", ip, allowedIp, username);
                return true;
            }
        }

        logger.warn("IP {} 不在用户 {} 的白名单 [{}] 中，拒绝访问", ip, username, allowedIpsStr);
        return false;
    }

    /**
     * 检查IP是否在任何已启用用户的白名单中（用于未登录状态，如登录页面）
     */
    private boolean isIpInAnyUserWhitelist(String ip) {
        try {
            // 使用现有的 selectAllActive 方法查询所有启用的用户
            List<MonitorUser> users = monitorUserMapper.selectAllActive();
            if (users == null || users.isEmpty()) {
                logger.warn("没有找到任何已启用的用户，拒绝IP {} 访问", ip);
                return false;
            }

            for (MonitorUser user : users) {
                String allowedIpsStr = user.getAllowedIps();
                if (allowedIpsStr == null || allowedIpsStr.trim().isEmpty()) {
                    continue;
                }

                String[] allowedIps = allowedIpsStr.split(",");
                for (String allowedIp : allowedIps) {
                    allowedIp = allowedIp.trim();
                    if (allowedIp.isEmpty()) continue;

                    if (isIpMatch(ip, allowedIp)) {
                        logger.info("IP {} 匹配用户 {} 的白名单规则 {}，允许访问", ip, user.getUsername(), allowedIp);
                        return true;
                    }
                }
            }

            logger.warn("IP {} 不在任何用户的白名单中，拒绝访问", ip);
            return false;
        } catch (Exception e) {
            logger.error("检查IP白名单失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 检查IP是否匹配白名单规则
     * @param ip 客户端IP
     * @param pattern 白名单规则（支持单个IP或IP段，如 192.168.1.* 或 192.168.1.100）
     * @return true=匹配
     */
    private boolean isIpMatch(String ip, String pattern) {
        // 精确匹配
        if (pattern.equals(ip)) {
            return true;
        }
        // IP段匹配（如 192.168.1.*）
        if (pattern.endsWith(".*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            if (ip.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLogin(HttpSession session) {
        return session != null && Boolean.TRUE.equals(session.getAttribute("MONITOR_LOGIN"));
    }
    private String getCurrentUsername(HttpSession session) {
        return session != null ? (String) session.getAttribute("MONITOR_USERNAME") : null;
    }


    @GetMapping("/monitor/login")
    public String loginPage(HttpServletRequest request) {
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            return "<h1 style='color:red;text-align:center;margin-top:100px;'>❌ 仅允许内网访问<br/>您的IP：" + clientIp + "</h1>";
        }
        return generateLoginHtml();
    }

    @PostMapping("/monitor/login")
    public Map<String, Object> doLogin(@RequestParam String username, @RequestParam String password, HttpSession session) {
        Map<String, Object> res = new HashMap<>();

        MonitorUser user = monitorUserMapper.selectByUsername(username);

        if (user != null && user.getStatus() == 1 && user.getPassword().equals(password)) {
            session.setAttribute("MONITOR_LOGIN", true);
            session.setAttribute("MONITOR_USERNAME", username);
            session.setAttribute("MONITOR_ROLE", user.getRole());
            res.put("code", 200);
            res.put("msg", "登录成功");
        } else {
            res.put("code", 401);
            res.put("msg", "账号或密码错误");
        }
        return res;
    }

    @GetMapping("/monitor/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/monitor/login";
    }

    @GetMapping("/monitor/index")
    public String index(HttpServletRequest request) {
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            return "<h1 style='color:red;text-align:center;margin-top:100px;'>❌ 仅允许内网访问<br/>您的IP：" + clientIp + "</h1>";
        }
        if (!isLogin(session)) {
            return "<script>location.href='/monitor/login';</script>";
        }
        return generateIndexHtml();
    }

    @GetMapping("/monitor/dashboard")
    public String dashboard(HttpServletRequest request) {
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            return "<h1 style='color:red;text-align:center;margin-top:100px;'>❌ 仅允许内网访问<br/>您的IP：" + clientIp + "</h1>";
        }
        if (!isLogin(request.getSession())) {
            return "<script>location.href='/monitor/login';</script>";
        }
        return generateDashboardHtml();
    }

    @GetMapping("/monitor/login-audit")
    public String loginAudit(HttpServletRequest request) {
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            return "<h1 style='color:red;text-align:center;margin-top:100px;'>❌ 仅允许内网访问<br/>您的IP：" + clientIp + "</h1>";
        }
        if (!isLogin(request.getSession())) {
            return "<script>location.href='/monitor/login';</script>";
        }
        return generateLoginAuditHtml();
    }

    @GetMapping("/monitor/customer-management")
    public String customerManagement(HttpServletRequest request) {
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            return "<h1 style='color:red;text-align:center;margin-top:100px;'>❌ 仅允许内网访问<br/>您的IP：" + clientIp + "</h1>";
        }
        if (!isLogin(session)) {
            return "<script>location.href='/monitor/login';</script>";
        }
        return generateCustomerManagementHtml();
    }

    @GetMapping("/monitor/user-management")
    public String userManagement(HttpServletRequest request) {
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            return "<h1 style='color:red;text-align:center;margin-top:100px;'>❌ 仅允许内网访问<br/>您的IP：" + clientIp + "</h1>";
        }
        if (!isLogin(session)) {
            return "<script>location.href='/monitor/login';</script>";
        }
        return generateUserManagementHtml();
    }

    @GetMapping("/monitor/number-files")
    public String numberFiles(HttpServletRequest request) {
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            return "<h1 style='color:red;text-align:center;margin-top:100px;'>❌ 仅允许内网访问<br/>您的IP：" + clientIp + "</h1>";
        }
        if (!isLogin(request.getSession())) {
            return "<script>location.href='/monitor/login';</script>";
        }
        return generateNumberFilesHtml();
    }

    @GetMapping("/monitor/ops-management")
    public String opsManagement(HttpServletRequest request) {
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            return "<h1 style='color:red;text-align:center;margin-top:100px;'>❌ 仅允许内网访问<br/>您的IP：" + clientIp + "</h1>";
        }
        if (!isLogin(session)) {
            return "<script>location.href='/monitor/login';</script>";
        }
        return generateOpsManagementHtml();
    }

    @GetMapping("/monitor/queue-management")
    public String queueManagement(HttpServletRequest request) {
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            return "<h1 style='color:red;text-align:center;margin-top:100px;'>❌ 仅允许内网访问<br/>您的IP：" + clientIp + "</h1>";
        }
        if (!isLogin(session)) {
            return "<script>location.href='/monitor/login';</script>";
        }
        return generateQueueManagementHtml();
    }

    @GetMapping("/monitor/queue-monitor")
    public String queueMonitor(HttpServletRequest request) {
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            return "<h1 style='color:red;text-align:center;margin-top:100px;'>❌ 仅允许内网访问<br/>您的IP：" + clientIp + "</h1>";
        }
        if (!isLogin(session)) {
            return "<script>location.href='/monitor/login';</script>";
        }
        return generateQueueMonitorHtml();
    }

    @GetMapping("/api/monitor/logs")
    public Map<String, Object> getLogMonitorData(@RequestParam(required = false) String secret, HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问，您的IP：" + clientIp);
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        result.put("serviceStatus", "UP");
        result.put("serviceName", "TexCell SMS Service");
        result.put("runDuration", LogMonitorTask.getRunDuration());
        result.put("startTime", LogMonitorTask.getStartTimeStr());
        result.put("systemMetrics", LogMonitorTask.getSystemMetrics());
        result.put("recentErrors", LogMonitorTask.recentErrors);
        result.put("recentGcLogs", LogMonitorTask.recentGcLogs);
        result.put("recentLongSms", LogMonitorTask.recentLongSms);
        result.put("recentApiLogs", LogMonitorTask.recentApiLogs);
        result.put("attackStatistics", SecurityMonitorInterceptor.getAttackStatistics());
        result.put("statistics", logMonitorTask.getStatistics());
        result.put("clientIp", clientIp);

        // 查询最近50条登录日志
        try {
            List<LoginAuditLog> recentLoginLogs = loginAuditLogMapper.selectPage(0, 50, null, null, null, null, null, null);
            result.put("recentLoginLogs", recentLoginLogs);
            result.put("totalLoginCount", (long) recentLoginLogs.size());
        } catch (Exception e) {
            logger.error("查询登录记录失败: {}", e.getMessage());
            result.put("recentLoginLogs", new ArrayList<>());
            result.put("totalLoginCount", 0L);
        }

        return result;
    }

    @GetMapping("/api/monitor/login-audit")
    public Map<String, Object> getLoginAuditLogs(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer result,
            @RequestParam(required = false) String twoFactorType,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String secret,
            HttpServletRequest request) {

        Map<String, Object> resultMap = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            resultMap.put("code", 403);
            resultMap.put("msg", "仅允许内网访问");
            return resultMap;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            resultMap.put("code", 401);
            resultMap.put("msg", "无权限访问");
            return resultMap;
        }

        try {
            int offset = (page - 1) * size;
            LocalDateTime start = null;
            LocalDateTime end = null;

            if (startTime != null && !startTime.trim().isEmpty()) {
                try {
                    String timeStr = startTime.trim();
                    if (timeStr.length() == 16) {
                        timeStr += ":00";
                    }
                    start = LocalDateTime.parse(timeStr, DATE_FORMATTER);
                    logger.info("解析开始时间成功: {}", start);
                } catch (Exception e) {
                    logger.error("解析开始时间失败: {}, 原始值: {}", e.getMessage(), startTime);
                }
            }
            if (endTime != null && !endTime.trim().isEmpty()) {
                try {
                    String timeStr = endTime.trim();
                    if (timeStr.length() == 16) {
                        timeStr += ":00";
                    }
                    end = LocalDateTime.parse(timeStr, DATE_FORMATTER);
                    logger.info("解析结束时间成功: {}", end);
                } catch (Exception e) {
                    logger.error("解析结束时间失败: {}, 原始值: {}", e.getMessage(), endTime);
                }
            }

            List<LoginAuditLog> logs = loginAuditLogMapper.selectPage(offset, size, username, ip, result, twoFactorType, start, end);
            long total = loginAuditLogMapper.count(username, ip, result, twoFactorType, start, end);

            resultMap.put("code", 200);
            resultMap.put("data", logs);
            resultMap.put("page", page);
            resultMap.put("size", size);
            resultMap.put("total", total);
            resultMap.put("totalPages", (total + size - 1) / size);
        } catch (Exception e) {
            logger.error("查询失败: ", e);
            resultMap.put("code", 500);
            resultMap.put("msg", "查询失败: " + e.getMessage());
        }
        return resultMap;
    }

    @GetMapping("/api/monitor/login-stats")
    public Map<String, Object> getLoginStats(@RequestParam(required = false) String secret, HttpServletRequest request) {
        Map<String, Object> resultMap = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            resultMap.put("code", 403);
            resultMap.put("msg", "仅允许内网访问");
            return resultMap;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            resultMap.put("code", 401);
            resultMap.put("msg", "无权限访问");
            return resultMap;
        }

        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime todayStart = now.withHour(0).withMinute(0).withSecond(0);
            LocalDateTime weekStart = now.minusDays(7);
            LocalDateTime monthStart = now.minusDays(30);

            Map<String, Object> stats = new HashMap<>();
            stats.put("today", getStatsByTimeRange(todayStart, now));
            stats.put("week", getStatsByTimeRange(weekStart, now));
            stats.put("month", getStatsByTimeRange(monthStart, now));
            stats.put("twoFactorStats", loginAuditLogMapper.getTwoFactorStats(weekStart));
            stats.put("topFailIps", loginAuditLogMapper.getTopFailIps(weekStart, 10));
            stats.put("topFailUsernames", loginAuditLogMapper.getTopFailUsernames(weekStart, 10));
            stats.put("hourlyTrend", loginAuditLogMapper.getTodayHourlyDistribution());
            stats.put("todayActiveUsers", loginAuditLogMapper.getTodayActiveUsers());
            stats.put("todayTopFailIps", loginAuditLogMapper.getTodayTopFailIps());
            stats.put("todayTwoFactorStats", loginAuditLogMapper.getTodayTwoFactorStats());
            stats.put("todayActiveUserList", loginAuditLogMapper.getTodayActiveUserList());

            resultMap.put("code", 200);
            resultMap.put("data", stats);
        } catch (Exception e) {
            resultMap.put("code", 500);
            resultMap.put("msg", "统计失败: " + e.getMessage());
        }
        return resultMap;
    }


    @GetMapping("/api/monitor/login-audit/detail")
    public Map<String, Object> getLoginAuditDetail(
            @RequestParam Long id,
            @RequestParam(required = false) String secret,
            HttpServletRequest request) {

        Map<String, Object> resultMap = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            resultMap.put("code", 403);
            resultMap.put("msg", "仅允许内网访问");
            return resultMap;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            resultMap.put("code", 401);
            resultMap.put("msg", "无权限访问");
            return resultMap;
        }

        try {
            LoginAuditLog log = loginAuditLogMapper.selectById(id);
            if (log == null) {
                resultMap.put("code", 404);
                resultMap.put("msg", "日志不存在");
                return resultMap;
            }


            Map<String, Object> detail = new LinkedHashMap<>();

            // 基础信息
            detail.put("用户名", log.getUsername());
            detail.put("登录时间", log.getLoginTime());
            detail.put("登录结果", log.getLoginResult() == 1 ? "✅ 成功" : "❌ 失败");
            detail.put("结果码", log.getResultCode());
            detail.put("结果消息", log.getResultMessage());
            detail.put("登录耗时", log.getLoginDuration() != null ? log.getLoginDuration() + " ms" : "-");

            // 网络信息
            detail.put("真实IP", log.getRealIp());
            detail.put("IP地理位置", log.getIpLocation() != null ? log.getIpLocation() :
                    (log.getIpCountry() != null ? log.getIpCountry() + " " + (log.getIpCity() != null ? log.getIpCity() : "") : "-"));

            // 请求信息
            detail.put("请求方式", log.getRequestMethod());
            detail.put("请求路径", log.getRequestUri());
            detail.put("来源域名", log.getSourceDomain());

            // 2FA认证信息
            if (log.getTwoFactorType() != null) {
                detail.put("2FA类型", "GOOGLE".equals(log.getTwoFactorType()) ? "🔐 Google验证器" : "📧 邮箱验证");
                detail.put("2FA目标", log.getTwoFactorTarget());
                detail.put("2FA结果", log.getTwoFaResult() == null ? "-" : (log.getTwoFaResult() == 1 ? "成功" : "失败"));
            }

            // 设备信息
            if (log.getUserAgent() != null && !log.getUserAgent().isEmpty()) {
                // 简化 User-Agent 显示
                String ua = log.getUserAgent();
                if (ua.length() > 80) {
                    ua = ua.substring(0, 80) + "...";
                }
                detail.put("设备信息", ua);
            }

            resultMap.put("code", 200);
            resultMap.put("data", detail);
        } catch (Exception e) {
            logger.error("查询详情失败: ", e);
            resultMap.put("code", 500);
            resultMap.put("msg", "查询失败: " + e.getMessage());
        }
        return resultMap;
    }


    @GetMapping("/api/monitor/login-audit/export")
    public void exportLoginAuditLogs(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer result,
            @RequestParam(required = false) String twoFactorType,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String secret,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write("<h1 style='color:red'>❌ 仅允许内网访问</h1>");
            return;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write("<h1 style='color:red'>❌ 无权限访问</h1>");
            return;
        }

        try {
            // 解析时间参数
            LocalDateTime start = null;
            LocalDateTime end = null;

            if (startTime != null && !startTime.trim().isEmpty()) {
                String timeStr = startTime.trim();
                if (timeStr.length() == 16) timeStr += ":00";
                start = LocalDateTime.parse(timeStr, DATE_FORMATTER);
            }
            if (endTime != null && !endTime.trim().isEmpty()) {
                String timeStr = endTime.trim();
                if (timeStr.length() == 16) timeStr += ":00";
                end = LocalDateTime.parse(timeStr, DATE_FORMATTER);
            }

            // 查询所有符合条件的日志
            List<LoginAuditLog> logs = loginAuditLogMapper.selectForExport(username, ip, result, twoFactorType, start, end);

            // 设置响应头
            response.setContentType("text/csv;charset=UTF-8");
            String fileName = "login_audit_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".csv";
            response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
            response.setCharacterEncoding("UTF-8");

            // 写入BOM头解决中文乱码
            response.getWriter().write('\uFEFF');

            // 写入CSV表头
            StringBuilder sb = new StringBuilder();
            sb.append("登录时间,账号,真实IP,IP地理位置,登录结果,结果码,结果消息,2FA类型,2FA目标,2FA结果,请求方式,请求路径,来源域名,User-Agent,登录耗时(ms),结果码说明\n");

            // 写入数据
            for (LoginAuditLog log : logs) {
                // 结果码说明
                String resultCodeMsg = getResultCodeMessage(log.getResultCode());

                sb.append(formatCsvValue(log.getLoginTime())).append(",");
                sb.append(formatCsvValue(log.getUsername())).append(",");
                sb.append(formatCsvValue(log.getRealIp())).append(",");

                String location = log.getIpLocation() != null ? log.getIpLocation() :
                        (log.getIpCountry() != null ? log.getIpCountry() + (log.getIpCity() != null ? log.getIpCity() : "") : "-");
                sb.append(formatCsvValue(location)).append(",");
                sb.append(log.getLoginResult() == 1 ? "成功" : "失败").append(",");
                sb.append(formatCsvValue(log.getResultCode())).append(",");
                sb.append(formatCsvValue(log.getResultMessage())).append(",");
                sb.append(log.getTwoFactorType() != null ? ("GOOGLE".equals(log.getTwoFactorType()) ? "Google验证器" : "邮箱验证") : "-").append(",");
                sb.append(formatCsvValue(log.getTwoFactorTarget())).append(",");
                sb.append(log.getTwoFaResult() == null ? "-" : (log.getTwoFaResult() == 1 ? "成功" : "失败")).append(",");
                sb.append(formatCsvValue(log.getRequestMethod())).append(",");
                sb.append(formatCsvValue(log.getRequestUri())).append(",");
                sb.append(formatCsvValue(log.getSourceDomain())).append(",");
                // User-Agent 可能包含逗号，需要处理
                String userAgent = log.getUserAgent();
                if (userAgent != null && userAgent.length() > 200) {
                    userAgent = userAgent.substring(0, 200) + "...";
                }
                sb.append(formatCsvValue(userAgent)).append(",");
                sb.append(log.getLoginDuration() != null ? log.getLoginDuration() : "-").append(",");
                sb.append(formatCsvValue(resultCodeMsg));
                sb.append("\n");
            }

            response.getWriter().write(sb.toString());
        } catch (Exception e) {
            logger.error("导出失败: ", e);
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write("<h1 style='color:red'>导出失败: " + e.getMessage() + "</h1>");
        }
    }

    @GetMapping("/api/monitor/number-files")
    public Map<String, Object> getNumberFiles(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String secret,
            HttpServletRequest request) {

        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            int offset = (page - 1) * size;
            LocalDateTime start = null;
            LocalDateTime end = null;

            if (startTime != null && !startTime.trim().isEmpty()) {
                start = LocalDateTime.parse(startTime.trim(), DATE_FORMATTER);
            }
            if (endTime != null && !endTime.trim().isEmpty()) {
                end = LocalDateTime.parse(endTime.trim(), DATE_FORMATTER);
            }

            // 模糊查询：前后加 %
            String likeUsername = null;
            if (username != null && !username.trim().isEmpty()) {
                likeUsername = "%" + username.trim() + "%";
            }

            List<CustomerNumberFile> list = customerNumberFileMapper.selectPageLike(offset, size, likeUsername, start, end);
            long total = customerNumberFileMapper.countForPageLike(likeUsername, start, end);

            result.put("code", 200);
            result.put("data", list);
            result.put("page", page);
            result.put("size", size);
            result.put("total", total);
            result.put("totalPages", (total + size - 1) / size);
        } catch (Exception e) {
            logger.error("查询号码文件列表失败", e);
            result.put("code", 500);
            result.put("msg", "查询失败: " + e.getMessage());
        }
        return result;
    }

    // 下载客户号码文件
    @GetMapping("/api/monitor/number-files/download")
    public void downloadNumberFile(
            @RequestParam Long id,
            @RequestParam(required = false) String secret,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write("<h1 style='color:red'>❌ 仅允许内网访问</h1>");
            return;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write("<h1 style='color:red'>❌ 无权限访问</h1>");
            return;
        }

        try {
            CustomerNumberFile record = customerNumberFileMapper.selectById(id);
            if (record == null) {
                response.setContentType("text/html;charset=UTF-8");
                response.getWriter().write("<h1 style='color:red'>文件记录不存在</h1>");
                return;
            }

            File file = new File(record.getFilePath());
            if (!file.exists()) {
                response.setContentType("text/html;charset=UTF-8");
                response.getWriter().write("<h1 style='color:red'>文件已被清理或不存在</h1>");
                return;
            }

            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"" + URLEncoder.encode(record.getFileName() + ".txt", "UTF-8") + "\"");
            response.setContentLengthLong(file.length());

            try (java.io.FileInputStream fis = new java.io.FileInputStream(file);
                 java.io.OutputStream os = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                }
                os.flush();
            }
        } catch (Exception e) {
            logger.error("下载文件失败", e);
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write("<h1 style='color:red'>下载失败: " + e.getMessage() + "</h1>");
        }
    }

    // 获取所有客户账号列表
    @GetMapping("/api/monitor/number-files/usernames")
    public Map<String, Object> getNumberFileUsernames(
            @RequestParam(required = false) String secret,
            HttpServletRequest request) {

        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            List<String> usernames = customerNumberFileMapper.getAllUsernames();
            result.put("code", 200);
            result.put("data", usernames);
        } catch (Exception e) {
            logger.error("获取客户账号列表失败", e);
            result.put("code", 500);
            result.put("msg", "查询失败: " + e.getMessage());
        }
        return result;

    }

    @GetMapping("/api/monitor/number-files/stats")
    public Map<String, Object> getNumberFilesStats(
            @RequestParam(required = false) String secret,
            HttpServletRequest request) {

        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            Map<String, Object> stats = customerNumberFileMapper.getStatistics();
            result.put("code", 200);
            result.put("data", stats);
        } catch (Exception e) {
            logger.error("获取统计失败", e);
            result.put("code", 500);
            result.put("msg", "查询失败: " + e.getMessage());
        }
        return result;
    }

    // ==================== API客户管理 API ====================

    @GetMapping("/api/monitor/customers")
    public Map<String, Object> getCustomers(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String secret,
            HttpServletRequest request) {

        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            int offset = (page - 1) * size;
            List<CustomerApiClient> list = customerApiClientMapper.selectPage(offset, size, username, status);
            long total = customerApiClientMapper.countForPage(username, status);

            result.put("code", 200);
            result.put("data", list);
            result.put("page", page);
            result.put("size", size);
            result.put("total", total);
            result.put("totalPages", (total + size - 1) / size);
        } catch (Exception e) {
            logger.error("查询客户列表失败", e);
            result.put("code", 500);
            result.put("msg", "查询失败: " + e.getMessage());
        }
        return result;
    }

    @GetMapping("/api/monitor/customers/statistics")
    public Map<String, Object> getCustomerStatistics(
            @RequestParam(required = false) String secret,
            HttpServletRequest request) {

        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            Map<String, Object> stats = customerApiClientMapper.getStatistics();
            result.put("code", 200);
            result.put("data", stats);
        } catch (Exception e) {
            logger.error("获取客户统计失败", e);
            result.put("code", 500);
            result.put("msg", "查询失败: " + e.getMessage());
        }
        return result;
    }

    @PostMapping("/api/monitor/customers")
    public Map<String, Object> addCustomer(@RequestBody CustomerApiClient customer,
                                           @RequestParam(required = false) String secret,
                                           HttpServletRequest request) {

        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            // 检查用户名是否存在
            CustomerApiClient existing = customerApiClientMapper.selectByUsername(customer.getUsername());
            if (existing != null) {
                result.put("code", 400);
                result.put("msg", "用户名已存在");
                return result;
            }
            customerApiClientMapper.insert(customer);
            // 刷新配置
            apiClientConfig.refresh();
            result.put("code", 200);
            result.put("msg", "添加成功");
        } catch (Exception e) {
            logger.error("添加客户失败", e);
            result.put("code", 500);
            result.put("msg", "添加失败: " + e.getMessage());
        }
        return result;
    }

    @PutMapping("/api/monitor/customers")
    public Map<String, Object> updateCustomer(@RequestBody CustomerApiClient customer,
                                              @RequestParam(required = false) String secret,
                                              HttpServletRequest request) {

        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            // 如果密码为空，先查询原密码
            if (customer.getPassword() == null || customer.getPassword().trim().isEmpty()) {
                CustomerApiClient existing = customerApiClientMapper.selectById(customer.getId());
                if (existing != null) {
                    customer.setPassword(existing.getPassword());
                }
            }
            customerApiClientMapper.update(customer);
            // 刷新配置
            apiClientConfig.refresh();
            result.put("code", 200);
            result.put("msg", "更新成功");
        } catch (Exception e) {
            logger.error("更新客户失败", e);
            result.put("code", 500);
            result.put("msg", "更新失败: " + e.getMessage());
        }
        return result;
    }

    @DeleteMapping("/api/monitor/customers")
    public Map<String, Object> deleteCustomer(@RequestParam Long id,
                                              @RequestParam(required = false) String secret,
                                              HttpServletRequest request) {

        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            customerApiClientMapper.deleteById(id);
            // 刷新配置
            apiClientConfig.refresh();
            result.put("code", 200);
            result.put("msg", "删除成功");
        } catch (Exception e) {
            logger.error("删除客户失败", e);
            result.put("code", 500);
            result.put("msg", "删除失败: " + e.getMessage());
        }
        return result;
    }

// ==================== 登录账户管理 API ====================

    @GetMapping("/api/monitor/users")
    public Map<String, Object> getMonitorUsers(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String secret,
            HttpServletRequest request) {

        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            int offset = (page - 1) * size;
            List<MonitorUser> list = monitorUserMapper.selectPage(offset, size, username, status);
            long total = monitorUserMapper.countForPage(username, status);

            result.put("code", 200);
            result.put("data", list);
            result.put("page", page);
            result.put("size", size);
            result.put("total", total);
            result.put("totalPages", (total + size - 1) / size);
        } catch (Exception e) {
            logger.error("查询用户列表失败", e);
            result.put("code", 500);
            result.put("msg", "查询失败: " + e.getMessage());
        }
        return result;
    }

    @PostMapping("/api/monitor/users")
    public Map<String, Object> addMonitorUser(@RequestBody MonitorUser user,
                                              @RequestParam(required = false) String secret,
                                              HttpServletRequest request) {

        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            MonitorUser existing = monitorUserMapper.selectByUsername(user.getUsername());
            if (existing != null) {
                result.put("code", 400);
                result.put("msg", "用户名已存在");
                return result;
            }
            monitorUserMapper.insert(user);
            result.put("code", 200);
            result.put("msg", "添加成功");
        } catch (Exception e) {
            logger.error("添加用户失败", e);
            result.put("code", 500);
            result.put("msg", "添加失败: " + e.getMessage());
        }
        return result;
    }

    @PutMapping("/api/monitor/users")
    public Map<String, Object> updateMonitorUser(@RequestBody MonitorUser user,
                                                 @RequestParam(required = false) String secret,
                                                 HttpServletRequest request) {

        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            // 如果密码为空，先查询原密码
            if (user.getPassword() == null || user.getPassword().trim().isEmpty()) {
                MonitorUser existing = monitorUserMapper.selectById(user.getId());
                if (existing != null) {
                    user.setPassword(existing.getPassword());
                }
            }
            monitorUserMapper.update(user);
            result.put("code", 200);
            result.put("msg", "更新成功");
        } catch (Exception e) {
            logger.error("更新用户失败", e);
            result.put("code", 500);
            result.put("msg", "更新失败: " + e.getMessage());
        }
        return result;
    }

    @DeleteMapping("/api/monitor/users")
    public Map<String, Object> deleteMonitorUser(@RequestParam Long id,
                                                 @RequestParam(required = false) String secret,
                                                 HttpServletRequest request) {

        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            monitorUserMapper.deleteById(id);
            result.put("code", 200);
            result.put("msg", "删除成功");
        } catch (Exception e) {
            logger.error("删除用户失败", e);
            result.put("code", 500);
            result.put("msg", "删除失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/api/monitor/current-user")
    public Map<String, Object> getCurrentUser(HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String username = getCurrentUsername(session);
        String role = (String) session.getAttribute("MONITOR_ROLE");

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("username", username);
        userInfo.put("role", role != null ? role : "ADMIN");

        result.put("code", 200);
        result.put("data", userInfo);
        return result;
    }

    /**
     * 重置用户密码（单独接口）
     */
    @PostMapping("/api/monitor/users/reset-password")
    public Map<String, Object> resetPassword(@RequestBody Map<String, Object> params,
                                             @RequestParam(required = false) String secret,
                                             HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "IP不在白名单中");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            Long id = ((Number) params.get("id")).longValue();
            String password = (String) params.get("password");

            if (password == null || password.trim().isEmpty()) {
                result.put("code", 400);
                result.put("msg", "密码不能为空");
                return result;
            }

            MonitorUser user = monitorUserMapper.selectById(id);
            if (user == null) {
                result.put("code", 404);
                result.put("msg", "用户不存在");
                return result;
            }

            // 权限检查：NOC只能重置自己的密码
            String currentRole = (String) session.getAttribute("MONITOR_ROLE");
            if (!"ADMIN".equals(currentRole) && !user.getUsername().equals(loginUsername)) {
                result.put("code", 403);
                result.put("msg", "权限不足，只能重置自己的密码");
                return result;
            }

            user.setPassword(password);
            monitorUserMapper.update(user);

            result.put("code", 200);
            result.put("msg", "密码重置成功");
        } catch (Exception e) {
            logger.error("重置密码失败", e);
            result.put("code", 500);
            result.put("msg", "重置失败: " + e.getMessage());
        }
        return result;
    }

    // ==================== 解除限制 API ====================

    // ==================== 运维管理 API ====================

    /**
     * 记录运维操作日志
     */
    private void recordOpsLog(String operationType, String operationName, String target,
                              boolean success, String resultMsg, HttpServletRequest request) {
        try {
            HttpSession session = request.getSession();
            String operator = getCurrentUsername(session);
            if (operator == null) operator = "未知";

            OpsAuditLog log = new OpsAuditLog();
            log.setOperator(operator);
            log.setOperationType(operationType);
            log.setOperationName(operationName);
            log.setTarget(target);
            log.setResult(success ? 1 : 0);
            log.setResultMsg(resultMsg);
            log.setRequestIp(getRealIp(request));
            log.setUserAgent(request.getHeader("User-Agent"));

            opsAuditLogMapper.insert(log);
            logger.info("运维操作日志记录成功: {} - {} - {}", operator, operationName, target);
        } catch (Exception e) {
            logger.error("记录运维操作日志失败: {}", e.getMessage());
        }
    }

    /**
     * 重置2FA（解绑2FA）
     */
    @PostMapping("/api/monitor/ops/reset-2fa")
    public Map<String, Object> reset2FA(@RequestParam String username,
                                        @RequestParam(required = false) String secret,
                                        HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        NCacheDBLink db = null;
        boolean success = false;
        String resultMsg = "";
        try {
            db = NCacheDBLink.getNCacheDBLink();
            db.InitEnv();
            db.ConnectToCacheDB();
            // 调用重置2FA存储过程，返回 Map<String, Object>
            Map<String, Object> ret = db.prc_Set2FAKeyForCompanyId("", username);
            db.Disconnect();

            success = true;
            resultMsg = "重置2FA成功";
            result.put("code", 200);
            result.put("msg", "重置2FA成功");
            result.put("data", ret);
            logger.info("运维操作: 重置2FA, 操作人={}, 目标账号={}", loginUsername, username);
        } catch (Exception e) {
            logger.error("重置2FA失败", e);
            resultMsg = "重置2FA失败: " + e.getMessage();
            result.put("code", 500);
            result.put("msg", "重置2FA失败: " + e.getMessage());
        } finally {
            recordOpsLog(OPS_RESET_2FA, "重置2FA", username, success, resultMsg, request);
            if (db != null) {
                try { db.Disconnect(); } catch (Exception ignored) {}
                try { db.Destroy(); } catch (Exception ignored) {}
            }
        }
        return result;
    }

    /**
     * 解除2FA
     */
    @PostMapping("/api/monitor/ops/disable-2fa")
    public Map<String, Object> disable2FA(@RequestParam String username,
                                          @RequestParam(required = false) String secret,
                                          HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        NCacheDBLink db = null;
        boolean success = false;
        String resultMsg = "";
        try {
            db = NCacheDBLink.getNCacheDBLink();
            db.InitEnv();
            db.ConnectToCacheDB();
            // opType = "1" 表示解除2FA，返回 Map<String, Object>
            Map<String, Object> ret = db.prc_SetCompany2FA_Condition(username, "", "", "1");
            db.Disconnect();

            success = true;
            resultMsg = "解除2FA成功";
            result.put("code", 200);
            result.put("msg", "解除2FA成功");
            result.put("data", ret);
            logger.info("运维操作: 解除2FA, 操作人={}, 目标账号={}", loginUsername, username);
        } catch (Exception e) {
            logger.error("解除2FA失败", e);
            resultMsg = "解除2FA失败: " + e.getMessage();
            result.put("code", 500);
            result.put("msg", "解除2FA失败: " + e.getMessage());
        } finally {
            recordOpsLog(OPS_DISABLE_2FA, "解除2FA", username, success, resultMsg, request);
            if (db != null) {
                try { db.Disconnect(); } catch (Exception ignored) {}
                try { db.Destroy(); } catch (Exception ignored) {}
            }
        }
        return result;
    }

    /**
     * 解锁账户
     */
    @PostMapping("/api/monitor/ops/unlock-account")
    public Map<String, Object> unlockAccount(@RequestParam String username,
                                             @RequestParam(required = false) String secret,
                                             HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        NCacheDBLink db = null;
        boolean success = false;
        String resultMsg = "";
        try {
            db = NCacheDBLink.getNCacheDBLink();
            db.InitEnv();
            db.ConnectToCacheDB();
            // opType = "0" 表示解除账号锁定
            Map<String, Object> ret = db.prc_ResumeWEBAccountByStaff(username, loginUsername, "0");
            db.Disconnect();

            success = true;
            resultMsg = "解锁账户成功";
            result.put("code", 200);
            result.put("msg", "解锁账户成功");
            result.put("data", ret);
            logger.info("运维操作: 解锁账户, 操作人={}, 目标账号={}", loginUsername, username);
        } catch (Exception e) {
            logger.error("解锁账户失败", e);
            resultMsg = "解锁账户失败: " + e.getMessage();
            result.put("code", 500);
            result.put("msg", "解锁账户失败: " + e.getMessage());
        } finally {
            recordOpsLog(OPS_UNLOCK_ACCOUNT, "解锁账户", username, success, resultMsg, request);
            if (db != null) {
                try { db.Disconnect(); } catch (Exception ignored) {}
                try { db.Destroy(); } catch (Exception ignored) {}
            }
        }
        return result;
    }

    /**
     * 解锁IP
     */
    @PostMapping("/api/monitor/ops/unlock-ip")
    public Map<String, Object> unlockIp(@RequestParam String username,
                                        @RequestParam(required = false) String secret,
                                        HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        NCacheDBLink db = null;
        boolean success = false;
        String resultMsg = "";
        try {
            db = NCacheDBLink.getNCacheDBLink();
            db.InitEnv();
            db.ConnectToCacheDB();
            // opType = "1" 表示解除IP锁定
            Map<String, Object> ret = db.prc_ResumeWEBAccountByStaff(username, loginUsername, "1");
            db.Disconnect();

            success = true;
            resultMsg = "解锁IP成功";
            result.put("code", 200);
            result.put("msg", "解锁IP成功");
            result.put("data", ret);
            logger.info("运维操作: 解锁IP, 操作人={}, 目标={}", loginUsername, username);
        } catch (Exception e) {
            logger.error("解锁IP失败", e);
            resultMsg = "解锁IP失败: " + e.getMessage();
            result.put("code", 500);
            result.put("msg", "解锁IP失败: " + e.getMessage());
        } finally {
            recordOpsLog(OPS_UNLOCK_IP, "解锁IP", username, success, resultMsg, request);
            if (db != null) {
                try { db.Disconnect(); } catch (Exception ignored) {}
                try { db.Destroy(); } catch (Exception ignored) {}
            }
        }
        return result;
    }

    // ==================== 运维日志 API ====================

    /**
     * 查询运维操作日志（分页）
     */
    @GetMapping("/api/monitor/ops-logs")
    public Map<String, Object> getOpsLogs(
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String secret,
            HttpServletRequest request) {

        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            int offset = (page - 1) * size;
            LocalDateTime start = null;
            LocalDateTime end = null;

            if (startTime != null && !startTime.trim().isEmpty()) {
                try {
                    String timeStr = startTime.trim();
                    if (timeStr.length() == 16) timeStr += ":00";
                    start = LocalDateTime.parse(timeStr, DATE_FORMATTER);
                } catch (Exception e) {
                    logger.error("解析开始时间失败: {}", startTime);
                }
            }
            if (endTime != null && !endTime.trim().isEmpty()) {
                try {
                    String timeStr = endTime.trim();
                    if (timeStr.length() == 16) timeStr += ":00";
                    end = LocalDateTime.parse(timeStr, DATE_FORMATTER);
                } catch (Exception e) {
                    logger.error("解析结束时间失败: {}", endTime);
                }
            }

            List<OpsAuditLog> logs = opsAuditLogMapper.selectPage(offset, size, operator, operationType, start, end);
            long total = opsAuditLogMapper.count(operator, operationType, start, end);

            result.put("code", 200);
            result.put("data", logs);
            result.put("page", page);
            result.put("size", size);
            result.put("total", total);
            result.put("totalPages", (total + size - 1) / size);
        } catch (Exception e) {
            logger.error("查询运维日志失败", e);
            result.put("code", 500);
            result.put("msg", "查询失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 获取运维操作统计数据（用于图表）
     */
    @GetMapping("/api/monitor/ops-logs/stats")
    public Map<String, Object> getOpsLogsStats(@RequestParam(required = false) String secret, HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            Map<String, Object> stats = new HashMap<>();
            stats.put("weekCompare", opsAuditLogMapper.getWeekCompareStats());
            stats.put("monthStatsByType", opsAuditLogMapper.getCurrentMonthStatsByType());
            stats.put("monthStatsByOperator", opsAuditLogMapper.getCurrentMonthStatsByOperator());
            stats.put("last7DaysTrend", opsAuditLogMapper.getLast7DaysTrend());

            result.put("code", 200);
            result.put("data", stats);
        } catch (Exception e) {
            logger.error("获取运维统计失败", e);
            result.put("code", 500);
            result.put("msg", "统计失败: " + e.getMessage());
        }
        return result;
    }

    // ==================== 发送队列管理 API ====================

    /**
     * 获取任务列表（内部管理）
     */
    @GetMapping("/api/monitor/queue/tasks")
    public Map<String, Object> getQueueTasksForAdmin(
            @RequestParam(required = false) String subAccount,
            @RequestParam(required = false) String taskId,
            @RequestParam(required = false) Integer scheduleStatus,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String secret,
            HttpServletRequest request) {

        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            Map<String, Object> data = smsQueueService.getAllQueueTasksForAdmin(
                    subAccount, taskId, scheduleStatus, pageNum, pageSize);
            result.put("code", 200);
            result.put("data", data);
        } catch (Exception e) {
            logger.error("查询任务列表失败", e);
            result.put("code", 500);
            result.put("msg", "查询失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 获取任务详情
     */
    @GetMapping("/api/monitor/queue/task/detail")
    public Map<String, Object> getTaskDetail(
            @RequestParam String taskId,
            @RequestParam(required = false) String callee,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String secret,
            HttpServletRequest request) {

        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            Map<String, Object> data = smsQueueService.getQueueTaskDetail(taskId, pageNum, pageSize, callee);
            result.put("code", 200);
            result.put("data", data);
        } catch (Exception e) {
            logger.error("查询任务详情失败", e);
            result.put("code", 500);
            result.put("msg", "查询失败: " + e.getMessage());
        }
        return result;
    }

    @GetMapping("/api/monitor/queue/customer-ranking")
    public Map<String, Object> getCustomerRanking(@RequestParam(required = false) String secret, HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            List<Map<String, Object>> ranking = smsQueueService.getCustomerRankingLastHalfHour();
            result.put("code", 200);
            result.put("data", ranking);
        } catch (Exception e) {
            logger.error("获取客户排行失败", e);
            result.put("code", 500);
            result.put("msg", "查询失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 导出任务全部详情（Excel）
     */
    @GetMapping("/api/monitor/queue/task/export")
    public void exportTaskDetails(
            @RequestParam String taskId,
            @RequestParam(required = false) String secret,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write("<h1 style='color:red'>❌ 仅允许内网访问</h1>");
            return;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write("<h1 style='color:red'>❌ 无权限访问</h1>");
            return;
        }

        try {
            smsQueueService.exportAllTaskDetailsByTaskId(taskId, response);
        } catch (Exception e) {
            logger.error("导出任务详情失败: {}", e.getMessage());
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write("<h1 style='color:red'>导出失败: " + e.getMessage() + "</h1>");
        }
    }

    /**
     * 删除任务（伪删除）
     */
    @DeleteMapping("/api/monitor/queue/task/delete")
    public Map<String, Object> deleteTask(
            @RequestParam String taskId,
            @RequestParam(required = false) String secret,
            HttpServletRequest request) {

        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            Set<String> taskIds = new HashSet<>();
            taskIds.add(taskId);
            int updated = smsQueueService.batchDeleteTaskForAdmin(taskIds);

            if (updated > 0) {
                result.put("code", 200);
                result.put("msg", "删除成功，已标记 " + updated + " 条任务为已删除");
                logger.info("运维操作: 伪删除任务(Admin), 操作人={}, 任务ID={}", loginUsername, taskId);
            } else {
                result.put("code", 404);
                result.put("msg", "未找到该任务或任务已被删除（仅限最近3天数据）");
            }
        } catch (Exception e) {
            logger.error("删除任务失败", e);
            result.put("code", 500);
            result.put("msg", "删除失败: " + e.getMessage());
        }
        return result;
    }


// ==================== 队列监控 API ====================

    /**
     * 获取队列监控告警列表（分页）
     */
    @GetMapping("/api/monitor/queue-monitor/list")
    public Map<String, Object> getQueueMonitorList(
            @RequestParam(required = false) String taskId,
            @RequestParam(required = false) String subAccount,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String alertLevel,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String secret,
            HttpServletRequest request) {

        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            int offset = (page - 1) * size;
            LocalDateTime start = null;
            LocalDateTime end = null;
            if (startTime != null && !startTime.trim().isEmpty()) {
                start = LocalDateTime.parse(startTime.trim(), DATE_FORMATTER);
            }
            if (endTime != null && !endTime.trim().isEmpty()) {
                end = LocalDateTime.parse(endTime.trim(), DATE_FORMATTER);
            }

            List<QueueMonitorLog> list = queueMonitorLogMapper.selectPage(offset, size, taskId, subAccount, status, alertLevel, start, end);
            long total = queueMonitorLogMapper.count(taskId, subAccount, status, alertLevel, start, end);
            int pendingCount = queueMonitorLogMapper.countPendingAlerts();

            result.put("code", 200);
            result.put("data", list);
            result.put("pendingCount", pendingCount);
            result.put("page", page);
            result.put("size", size);
            result.put("total", total);
            result.put("totalPages", (total + size - 1) / size);
        } catch (Exception e) {
            logger.error("查询队列监控列表失败", e);
            result.put("code", 500);
            result.put("msg", "查询失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 获取队列监控统计数据
     */
    @GetMapping("/api/monitor/queue-monitor/stats")
    public Map<String, Object> getQueueMonitorStats(@RequestParam(required = false) String secret,
                                                    HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            Map<String, Object> stats = new HashMap<>();
            stats.put("pendingCount", queueMonitorLogMapper.countPendingAlerts());
            stats.put("weekCompare", queueMonitorLogMapper.getWeekCompareStats());
            stats.put("monthStatsByLevel", queueMonitorLogMapper.getCurrentMonthStatsByLevel());
            stats.put("monthStatsByStatus", queueMonitorLogMapper.getCurrentMonthStatsByStatus());
            stats.put("last7DaysTrend", queueMonitorLogMapper.getLast7DaysTrend());
            stats.put("avgHandleTime", queueMonitorLogMapper.getAvgHandleTime());

            result.put("code", 200);
            result.put("data", stats);
        } catch (Exception e) {
            logger.error("获取队列监控统计失败", e);
            result.put("code", 500);
            result.put("msg", "统计失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 执行队列任务修复
     */
    @PostMapping("/api/monitor/queue-monitor/fix")
    public Map<String, Object> fixQueueTask(@RequestBody Map<String, Object> params,
                                            @RequestParam(required = false) String secret,
                                            HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            String taskId = (String) params.get("taskId");
            if (taskId == null || taskId.trim().isEmpty()) {
                result.put("code", 400);
                result.put("msg", "任务ID不能为空");
                return result;
            }

            // 执行修复SQL
            int updated = smsQueueMapper.executeFixSql(taskId);

            // 更新监控日志状态
            queueMonitorLogMapper.updateStatus(taskId, "PROCESSED", loginUsername, LocalDateTime.now());

            result.put("code", 200);
            result.put("msg", "修复成功");
            result.put("updated", updated);
            logger.info("队列任务修复: taskId={}, operator={}, updated={}", taskId, loginUsername, updated);
        } catch (Exception e) {
            logger.error("修复任务失败", e);
            result.put("code", 500);
            result.put("msg", "修复失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 忽略告警
     */
    @PostMapping("/api/monitor/queue-monitor/ignore")
    public Map<String, Object> ignoreQueueTask(@RequestBody Map<String, Object> params,
                                               @RequestParam(required = false) String secret,
                                               HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            String taskId = (String) params.get("taskId");
            if (taskId == null || taskId.trim().isEmpty()) {
                result.put("code", 400);
                result.put("msg", "任务ID不能为空");
                return result;
            }

            queueMonitorLogMapper.updateStatus(taskId, "IGNORED", loginUsername, LocalDateTime.now());
            result.put("code", 200);
            result.put("msg", "已忽略该告警");
        } catch (Exception e) {
            logger.error("忽略告警失败", e);
            result.put("code", 500);
            result.put("msg", "操作失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 手动触发扫描超时任务
     */
    @PostMapping("/api/monitor/queue-monitor/scan")
    public Map<String, Object> scanTimeoutTasks(@RequestParam(required = false) String secret,
                                                HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            // 1. 查询超时任务（30分钟和60分钟）
            List<Map<String, Object>> warningTasks = smsQueueMapper.findTimeoutTasks(30);
            List<Map<String, Object>> criticalTasks = smsQueueMapper.findTimeoutTasks(60);

            // 2. 获取当前所有待处理的任务ID（用于检测已恢复的任务）
            List<QueueMonitorLog> pendingLogs = queueMonitorLogMapper.findPendingAlerts();
            Set<String> pendingTaskIds = pendingLogs.stream()
                    .map(QueueMonitorLog::getTaskId)
                    .collect(Collectors.toSet());

            // 3. 当前超时任务ID集合
            Map<String, Map<String, Object>> allTasks = new LinkedHashMap<>();
            for (Map<String, Object> task : warningTasks) {
                allTasks.putIfAbsent((String) task.get("taskId"), task);
            }
            for (Map<String, Object> task : criticalTasks) {
                allTasks.put((String) task.get("taskId"), task);
            }
            Set<String> currentTimeoutTaskIds = allTasks.keySet();

            // 4. 自动关闭已恢复的任务（不再超时的任务）
            int autoClosed = 0;
            for (String taskId : pendingTaskIds) {
                if (!currentTimeoutTaskIds.contains(taskId)) {
                    queueMonitorLogMapper.updateStatus(taskId, "PROCESSED", "系统自动", LocalDateTime.now());
                    autoClosed++;
                    logger.info("手动扫描：任务 {} 已恢复，自动关闭告警", taskId);
                }
            }

            if (allTasks.isEmpty()) {
                result.put("code", 200);
                result.put("msg", "扫描完成，未发现超时任务，自动关闭 " + autoClosed + " 条告警");
                result.put("total", 0);
                result.put("newAlerts", 0);
                result.put("updatedAlerts", 0);
                result.put("autoClosed", autoClosed);
                return result;
            }

            // 5. 处理仍在超时中的任务
            int newAlerts = 0;
            int updatedAlerts = 0;
            int weChatAlerts = 0;

            for (Map<String, Object> task : allTasks.values()) {
                String taskId = (String) task.get("taskId");
                Integer totalCount = ((Number) task.getOrDefault("totalCount", 0)).intValue();
                Integer detailPendingCount = ((Number) task.getOrDefault("detailPendingCount", 0)).intValue();

                // 如果 detailPendingCount = 0，说明已经没有未更新的记录了，跳过
                if (detailPendingCount == 0) {
                    continue;
                }

                QueueMonitorLog existing = queueMonitorLogMapper.selectLatestByTaskId(taskId);

                if (existing != null) {
                    // 已处理或已忽略的，跳过
                    if ("PROCESSED".equals(existing.getStatus()) || "IGNORED".equals(existing.getStatus())) {
                        continue;
                    }

                    // 🔧 更新现有记录
                    QueueMonitorLog updatedLog = buildQueueMonitorLog(task);
                    updatedLog.setId(existing.getId());
                    queueMonitorLogMapper.update(updatedLog);
                    updatedAlerts++;

                    // 如果告警未发送过 且 totalCount > 5000，发送企微
                    if (totalCount > 5000 && existing.getAlertSent() == 0) {
                        sendQueueAlertToWeChat(updatedLog);
                        queueMonitorLogMapper.updateAlertSent(taskId);
                        weChatAlerts++;
                    }
                    continue;
                }

                // 新记录：插入
                QueueMonitorLog log = buildQueueMonitorLog(task);
                queueMonitorLogMapper.insert(log);

                if (totalCount > 5000) {
                    sendQueueAlertToWeChat(log);
                    queueMonitorLogMapper.updateAlertSent(taskId);
                    weChatAlerts++;
                }
                newAlerts++;
            }

            logger.info("手动扫描完成：新增 {} 条，更新 {} 条，自动关闭 {} 条，企微发送 {} 条",
                    newAlerts, updatedAlerts, autoClosed, weChatAlerts);

            result.put("code", 200);
            result.put("msg", String.format("扫描完成：新增 %d 条，更新 %d 条，自动关闭 %d 条，企微发送 %d 条",
                    newAlerts, updatedAlerts, autoClosed, weChatAlerts));
            result.put("total", allTasks.size());
            result.put("newAlerts", newAlerts);
            result.put("updatedAlerts", updatedAlerts);
            result.put("autoClosed", autoClosed);
            result.put("weChatAlerts", weChatAlerts);
        } catch (Exception e) {
            logger.error("扫描超时任务失败", e);
            result.put("code", 500);
            result.put("msg", "扫描失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 获取任务中未更新DR的号码列表（分页）
     */
    @GetMapping("/api/monitor/queue-monitor/pending-numbers")
    public Map<String, Object> getPendingDrNumbers(
            @RequestParam String taskId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String secret,
            HttpServletRequest request) {

        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            result.put("code", 403);
            result.put("msg", "仅允许内网访问");
            return result;
        }
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限访问");
            return result;
        }

        try {
            int offset = (page - 1) * size;

            // 查询号码列表
            List<Map<String, Object>> numbers = smsQueueMapper.findPendingDrNumbers(taskId, offset, size);

            // 查询总数
            long total = smsQueueMapper.countPendingDrNumbers(taskId);

            result.put("code", 200);
            result.put("data", numbers);
            result.put("total", total);
            result.put("page", page);
            result.put("size", size);
            result.put("totalPages", (total + size - 1) / size);
        } catch (Exception e) {
            logger.error("查询未更新DR号码失败", e);
            result.put("code", 500);
            result.put("msg", "查询失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 构建队列监控日志对象
     */
    private QueueMonitorLog buildQueueMonitorLog(Map<String, Object> task) {
        QueueMonitorLog log = new QueueMonitorLog();
        log.setTaskId((String) task.get("taskId"));
        log.setSubAccount((String) task.get("subAccount"));
        log.setTaskName((String) task.get("taskName"));
        log.setTotalCount(((Number) task.getOrDefault("totalCount", 0)).intValue());
        log.setSubmitSuccCount(((Number) task.getOrDefault("submitSuccCount", 0)).intValue());
        log.setDrSuccCount(((Number) task.getOrDefault("drSuccCount", 0)).intValue());
        log.setDrFailCount(((Number) task.getOrDefault("drFailCount", 0)).intValue());
        log.setPendingCount(((Number) task.getOrDefault("pendingCount", 0)).intValue());
        log.setDetailPendingCount(((Number) task.getOrDefault("detailPendingCount", 0)).intValue());

        // ✅ 从字符串解析 taskTime
        String taskTimeStr = (String) task.get("taskTimeStr");

        if (taskTimeStr != null && !taskTimeStr.trim().isEmpty()) {
            try {
                String timeStr = taskTimeStr.trim();
                // 如果有微秒，截断到秒
                if (timeStr.contains(".")) {
                    timeStr = timeStr.substring(0, timeStr.indexOf("."));
                }
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                LocalDateTime taskTime = LocalDateTime.parse(timeStr, formatter);
                log.setTaskTime(taskTime);
            } catch (Exception e) {
                log.setTaskTime(LocalDateTime.now().withNano(0));
            }
        } else {
            log.setTaskTime(LocalDateTime.now().withNano(0));
        }

        log.setElapsedMinutes(((Number) task.getOrDefault("elapsedMinutes", 0)).intValue());
        log.setAlertLevel(((Number) task.getOrDefault("elapsedMinutes", 0)).intValue() >= 60 ? "CRITICAL" : "WARNING");
        log.setAlertSent(0);
        log.setStatus("PENDING");
        return log;
    }

    /**
     * 发送队列告警到企业微信
     */
    private void sendQueueAlertToWeChat(QueueMonitorLog log) {
        try {
            // 检查是否启用
            if (!weChatRobotUtil.isEnabled()) {
                logger.info("企业微信机器人未启用，跳过发送");
                return;
            }

            String levelText = "CRITICAL".equals(log.getAlertLevel())
                    ? "🔴【严重】超过60分钟"
                    : "🟡【警告】超过30分钟";

            String msg = String.format(
                    "## 📨 队列任务DR未更新告警\n\n" +
                            "### %s\n\n" +
                            "| 项目 | 数值 |\n" +
                            "|------|------|\n" +
                            "| 任务ID | `%s` |\n" +
                            "| 客户账号 | %s |\n" +
                            "| 总发送数 | **%d** ⚠️ |\n" +
                            "| 已提交数 | %d |\n" +
                            "| 成功数 | %d |\n" +
                            "| 失败数 | %d |\n" +
                            "| **DR未更新数** | **%d 条** |\n" +
                            "| 已过时间 | **%d 分钟** |\n\n" +
                            "> ⏰ 任务已完成但部分DR一直未更新，请留意检查处理！\n\n" +
                            "> 🔗 [点击查看监控详情](https://internalsms.texcellsms.com:1443/monitor/queue-monitor)",
                    levelText,
                    log.getTaskId(),
                    log.getSubAccount(),
                    log.getTotalCount(),
                    log.getSubmitSuccCount(),
                    log.getDrSuccCount(),
                    log.getDrFailCount(),
                    log.getDetailPendingCount(),
                    log.getElapsedMinutes()
            );

            // 发送到企微
            boolean success = weChatRobotUtil.sendMarkdownMessage(msg);

            if (success) {
                logger.info("企微告警发送成功: taskId={}, level={}", log.getTaskId(), log.getAlertLevel());
            } else {
                logger.warn("企微告警发送失败: taskId={}", log.getTaskId());
            }

        } catch (Exception e) {
            logger.error("发送企微告警失败", e);
        }
    }

    private String formatCsvValue(Object value) {
        if (value == null) return "";
        String str = value.toString();
        // 如果包含逗号、换行符或双引号，需要用双引号包裹并转义
        if (str.contains(",") || str.contains("\n") || str.contains("\r") || str.contains("\"")) {
            str = str.replace("\"", "\"\"");
            return "\"" + str + "\"";
        }
        return str;
    }

    private String getResultCodeMessage(String resultCode) {
        if (resultCode == null) return "-";
        switch (resultCode) {
            case "0": return "登录成功";
            case "402": return "账号或密码错误";
            case "403": return "认证失败/两步验证获取失败";
            case "405": return "参数不完整";
            case "480": return "IP已锁定（多次失败）";
            case "502": return "CacheDB执行失败";
            case "603": return "账户已锁定";
            default: return "-";
        }
    }

    private Map<String, Object> getStatsByTimeRange(LocalDateTime start, LocalDateTime end) {
        Map<String, Object> stats = new HashMap<>();
        Map<String, Object> data = loginAuditLogMapper.getStatsByTimeRange(start, end);
        if (data != null) {
            long total = ((Number) data.getOrDefault("totalCount", 0L)).longValue();
            long success = ((Number) data.getOrDefault("successCount", 0L)).longValue();
            long fail = ((Number) data.getOrDefault("failCount", 0L)).longValue();
            stats.put("total", total);
            stats.put("success", success);
            stats.put("fail", fail);
            stats.put("successRate", total > 0 ? String.format("%.1f", success * 100.0 / total) : "0");
        } else {
            stats.put("total", 0L);
            stats.put("success", 0L);
            stats.put("fail", 0L);
            stats.put("successRate", "0");
        }
        return stats;
    }

    // ====================== HTML======================
    private String generateLoginHtml() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=no\">\n" +
                "<title>Texcell ECCOM WEB 监控系统登录</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='20' fill='%23667eea'/%3E%3Ctext x='50' y='68' font-size='50' text-anchor='middle' fill='white' font-family='Arial'%3E📊%3C/text%3E%3C/svg%3E\">\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:ital,wght@0,300;0,400;0,500;0,600;0,700;1,400&display=swap\" rel=\"stylesheet\">\n" +
                "<style>\n" +
                ":root {\n" +
                "  --bg-overlay: rgba(0,0,0,0.05);\n" +
                "  --card-bg: #ffffff;\n" +
                "  --text-primary: #1e293b;\n" +
                "  --text-secondary: #475569;\n" +
                "  --text-muted: #94a3b8;\n" +
                "  --input-border: #d1d5db;\n" +
                "  --input-bg: #ffffff;\n" +
                "  --error-bg: #fef2f2;\n" +
                "  --error-border: #fecaca;\n" +
                "  --error-text: #dc2626;\n" +
                "  --primary-color: #1677ff;\n" +
                "  --primary-hover: #4096ff;\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#f0f2f5;min-height:100vh;display:flex;justify-content:center;align-items:center;position:relative;overflow:hidden;}\n" +
                ".bg-orb{display:none;}\n" +
                ".grid-pattern{display:none;}\n" +
                "body::before{display:none;}\n" +
                ".login-container{position:relative;z-index:10;width:100%;max-width:450px;margin:20px;animation:fadeInUp 0.6s ease-out;}\n" +
                "@keyframes fadeInUp{from{opacity:0;transform:translateY(30px);}to{opacity:1;transform:translateY(0);}}\n" +
                ".login-card{background:#ffffff;border-radius:48px;padding:48px 40px;box-shadow:0 25px 50px -12px rgba(0,0,0,0.25);transition:all 0.3s ease;}\n" +
                ".login-card:hover{transform:translateY(-4px);box-shadow:0 35px 60px -15px rgba(0,0,0,0.3);}\n" +
                ".logo-area{text-align:center;margin-bottom:32px;}\n" +
                ".logo-icon{width:64px;height:64px;background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);border-radius:24px;display:flex;align-items:center;justify-content:center;margin:0 auto 16px;box-shadow:0 10px 20px -5px rgba(22,119,255,0.4);}\n" +
                ".logo-icon span{font-size:36px;}\n" +
                ".login-card h2{font-size:28px;font-weight:700;text-align:center;color:#1e293b;margin-bottom:8px;letter-spacing:-0.5px;}\n" +
                ".login-sub{text-align:center;color:#94a3b8;font-size:14px;margin-bottom:32px;font-weight:400;}\n" +
                ".input-group{margin-bottom:20px;}\n" +
                ".input-label{display:block;font-size:13px;font-weight:500;color:#475569;margin-bottom:8px;}\n" +
                ".input-wrapper{position:relative;}\n" +
                ".input-icon{position:absolute;left:16px;top:50%;transform:translateY(-50%);color:#94a3b8;font-size:18px;pointer-events:none;}\n" +
                ".input-wrapper input{width:100%;height:52px;padding:0 16px 0 48px;border:1.5px solid #d1d5db;border-radius:20px;font-size:15px;color:#1e293b;background:#ffffff;transition:all 0.3s;}\n" +
                ".input-wrapper input:focus{outline:none;border-color:#1677ff;box-shadow:0 0 0 4px rgba(22,119,255,0.1);}\n" +
                ".toggle-pwd{position:absolute;right:16px;top:50%;transform:translateY(-50%);cursor:pointer;color:#94a3b8;font-size:18px;background:none;border:none;}\n" +
                ".toggle-pwd:hover{color:#1677ff;}\n" +
                ".login-btn{width:100%;height:52px;background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);color:white;border:none;border-radius:26px;font-size:16px;font-weight:600;cursor:pointer;transition:all 0.3s;margin-top:8px;display:flex;align-items:center;justify-content:center;gap:8px;}\n" +
                ".login-btn:hover{transform:translateY(-2px);box-shadow:0 10px 25px -5px rgba(22,119,255,0.5);}\n" +
                ".login-btn:active{transform:translateY(0);}\n" +
                ".login-btn.loading{opacity:0.7;cursor:not-allowed;}\n" +
                ".login-btn .spinner{width:20px;height:20px;border:2px solid rgba(255,255,255,0.3);border-top-color:white;border-radius:50%;animation:spin 0.8s linear infinite;display:none;}\n" +
                ".login-btn.loading .spinner{display:inline-block;}\n" +
                ".login-btn.loading .btn-text{display:none;}\n" +
                "@keyframes spin{to{transform:rotate(360deg);}}\n" +
                ".error-msg{background:#fef2f2;border:1px solid #fecaca;border-radius:16px;padding:12px 16px;margin-top:20px;display:none;align-items:center;gap:10px;}\n" +
                ".error-msg.show{display:flex;}\n" +
                ".error-msg .error-icon{color:#dc2626;font-size:18px;}\n" +
                ".error-msg .error-text{color:#dc2626;font-size:13px;flex:1;}\n" +
                ".footer{text-align:center;margin-top:24px;color:#94a3b8;font-size:12px;}\n" +
                "@media(max-width:480px){.login-card{padding:32px 24px;}.login-card h2{font-size:24px;}.input-wrapper input{height:48px;}.login-btn{height:48px;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"bg-orb orb-1\"></div>\n" +
                "<div class=\"bg-orb orb-2\"></div>\n" +
                "<div class=\"bg-orb orb-3\"></div>\n" +
                "<div class=\"grid-pattern\"></div>\n" +
                "<div class=\"login-container\">\n" +
                "<div class=\"login-card\">\n" +
                "<div class=\"logo-area\">\n" +
                "<div class=\"logo-icon\"><span>📊</span></div>\n" +
                "<h2>ECCOM WEB 监控系统登录</h2>\n" +
                "<div class=\"login-sub\">TexCell SMS 服务监控平台</div>\n" +
                "</div>\n" +
                "<div class=\"input-group\">\n" +
                "<label class=\"input-label\">账号</label>\n" +
                "<div class=\"input-wrapper\">\n" +
                "<span class=\"input-icon\">👤</span>\n" +
                "<input type=\"text\" id=\"username\" placeholder=\"请输入账号\" autocomplete=\"off\" readonly onfocus=\"this.removeAttribute('readonly')\">\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"input-group\">\n" +
                "<label class=\"input-label\">密码</label>\n" +
                "<div class=\"input-wrapper\">\n" +
                "<span class=\"input-icon\">🔒</span>\n" +
                "<input type=\"password\" id=\"password\" placeholder=\"请输入密码\" autocomplete=\"off\" readonly onfocus=\"this.removeAttribute('readonly')\">\n" +
                "<button type=\"button\" class=\"toggle-pwd\" onclick=\"togglePassword()\">👁️</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "<button class=\"login-btn\" id=\"loginBtn\" onclick=\"login()\">\n" +
                "<span class=\"spinner\"></span>\n" +
                "<span class=\"btn-text\">登 录 系 统</span>\n" +
                "</button>\n" +
                "<div class=\"error-msg\" id=\"errorMsg\">\n" +
                "<span class=\"error-icon\">⚠️</span>\n" +
                "<span class=\"error-text\" id=\"errorText\"></span>\n" +
                "</div>\n" +
                "<div class=\"footer\">\n" +
                "🔐 仅限内部人员访问 | 请妥善保管账号密码\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<script>\n" +
                "function togglePassword(){var pwd=document.getElementById('password');var btn=document.querySelector('.toggle-pwd');if(pwd.type==='password'){pwd.type='text';btn.innerHTML='🙈';}else{pwd.type='password';btn.innerHTML='👁️';}}\n" +
                "function showError(msg){var errDiv=document.getElementById('errorMsg');var errText=document.getElementById('errorText');errText.innerText=msg;errDiv.classList.add('show');setTimeout(function(){errDiv.classList.remove('show');},3000);}\n" +
                "function login(){var u=document.getElementById('username');var p=document.getElementById('password');var username=u.value.trim();var password=p.value;if(!username){showError('请输入账号');u.focus();return;}if(!password){showError('请输入密码');p.focus();return;}var btn=document.getElementById('loginBtn');btn.classList.add('loading');fetch('/monitor/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'username='+encodeURIComponent(username)+'&password='+encodeURIComponent(password)}).then(function(res){return res.json();}).then(function(data){if(data.code===200){window.location.href='/monitor/index';}else{showError(data.msg||'账号或密码错误');btn.classList.remove('loading');}}).catch(function(){showError('登录失败，请重试');btn.classList.remove('loading');});}\n" +
                "document.getElementById('password').addEventListener('keypress',function(e){if(e.key==='Enter')login();});\n" +
                "setTimeout(function(){document.getElementById('username').value='';document.getElementById('password').value='';},100);\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }

    private String generateIndexHtml() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=no\">\n" +
                "<title>Texcell ECCOM WEB 监控首页</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='20' fill='%23667eea'/%3E%3Ctext x='50' y='68' font-size='50' text-anchor='middle' fill='white' font-family='Arial'%3E📊%3C/text%3E%3C/svg%3E\">\n" +
                "<script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:ital,wght@0,300;0,400;0,500;0,600;0,700;1,400&display=swap\" rel=\"stylesheet\">\n" +
                "<style>\n" +
                ":root {\n" +
                "  --bg-primary: #f5f7fa;\n" +
                "  --bg-card: #ffffff;\n" +
                "  --text-primary: #1e293b;\n" +
                "  --text-secondary: #64748b;\n" +
                "  --text-muted: #94a3b8;\n" +
                "  --border-color: #e8ecf1;\n" +
                "  --shadow: 0 1px 3px rgba(0,0,0,0.06);\n" +
                "  --navbar-bg: #ffffff;\n" +
                "  --hover-bg: rgba(0,0,0,0.02);\n" +
                "  --primary-color: #1677ff;\n" +
                "  --primary-hover: #4096ff;\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:#f5f7fa;font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;}\n" +
                ".navbar{background:#ffffff;box-shadow:0 1px 3px rgba(0,0,0,0.06);border-bottom:1px solid #e8ecf1;position:sticky;top:0;z-index:100;}\n" +
                ".nav-container{max-width:2000px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;padding:0 32px;height:70px;}\n" +
                ".logo{display:flex;align-items:center;gap:12px;}\n" +
                ".logo-icon{width:40px;height:40px;background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);border-radius:12px;display:flex;align-items:center;justify-content:center;font-size:20px;}\n" +
                ".logo-text{font-size:18px;font-weight:700;color:#1e293b;letter-spacing:1px;}\n" +
                ".logo-sub{font-size:11px;color:#94a3b8;margin-left:8px;}\n" +
                ".menu{display:flex;gap:12px;}\n" +
                ".menu a{color:#64748b;text-decoration:none;padding:8px 20px;border-radius:40px;transition:all 0.3s;font-weight:500;font-size:14px;}\n" +
                ".menu a:hover{background:#f1f5f9;color:#1e293b;}\n" +
                ".menu a.active{background:#e8f0fe;color:#1677ff;box-shadow:none;}\n" +
                ".user-info{display:flex;align-items:center;gap:20px;}\n" +
                ".user-name{color:#475569;font-size:14px;}\n" +
                ".user-role{background:rgba(22,119,255,0.1);padding:4px 12px;border-radius:20px;font-size:12px;color:#1677ff;}\n" +
                ".logout-btn{background:#f1f5f9;border:1px solid #e8ecf1;color:#475569;padding:8px 20px;border-radius:40px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".page-wrap{max-width:2000px;margin:0 auto;padding:32px;}\n" +
                ".welcome-card{background:#ffffff;border-radius:16px;padding:36px 40px;margin-bottom:32px;color:#1e293b;position:relative;overflow:hidden;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);}\n" +
                ".welcome-card::before{content:'📊';position:absolute;right:-20px;bottom:-30px;font-size:160px;opacity:0.06;pointer-events:none;}\n" +
                ".welcome-card h1{font-size:28px;font-weight:700;margin-bottom:8px;}\n" +
                ".welcome-card p{font-size:14px;color:#64748b;margin-bottom:16px;}\n" +
                ".welcome-stats{display:flex;gap:32px;margin-top:16px;}\n" +
                ".welcome-stat{display:flex;align-items:center;gap:8px;font-size:13px;color:#64748b;}\n" +
                ".stats-grid{display:grid;grid-template-columns:repeat(5,1fr);gap:20px;margin-bottom:32px;}\n" +
                ".stat-card{background:#ffffff;border-radius:12px;padding:24px 20px;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);transition:all 0.3s;}\n" +
                ".stat-card:hover{transform:translateY(-2px);box-shadow:0 4px 12px rgba(0,0,0,0.06);border-color:#1677ff;}\n" +
                ".stat-icon{font-size:28px;margin-bottom:12px;}\n" +
                ".stat-number{font-size:36px;font-weight:800;color:#1e293b;line-height:1;}\n" +
                ".stat-label{font-size:13px;color:#64748b;margin-top:8px;font-weight:500;}\n" +
                ".stat-trend{font-size:12px;margin-top:8px;padding-top:8px;border-top:1px solid #e8ecf1;color:#64748b;}\n" +
                ".section-title{font-size:18px;font-weight:700;color:#1e293b;margin-bottom:20px;display:flex;align-items:center;gap:8px;}\n" +
                ".section-title::before{content:'';width:4px;height:20px;background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);border-radius:4px;}\n" +
                ".chart-row{display:grid;grid-template-columns:1fr 1fr;gap:24px;margin-bottom:32px;}\n" +
                ".chart-card{background:#ffffff;border-radius:12px;padding:24px;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);transition:all 0.3s;}\n" +
                ".chart-card:hover{box-shadow:0 4px 12px rgba(0,0,0,0.06);}\n" +
                ".chart-card h3{font-size:16px;font-weight:600;color:#1e293b;margin-bottom:20px;display:flex;align-items:center;gap:8px;}\n" +
                ".user-list-card{background:#ffffff;border-radius:12px;padding:24px;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);margin-bottom:32px;}\n" +
                ".user-list-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:20px;flex-wrap:wrap;gap:12px;}\n" +
                ".user-list-header h3{font-size:16px;font-weight:600;color:#1e293b;display:flex;align-items:center;gap:8px;}\n" +
                ".user-badge{background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);color:white;border-radius:20px;padding:4px 12px;font-size:12px;font-weight:500;}\n" +
                ".user-items{display:flex;flex-wrap:wrap;gap:12px;}\n" +
                ".user-item{background:#f8fafc;border:1px solid #e8ecf1;border-radius:40px;padding:8px 20px;font-size:14px;color:#1e293b;transition:all 0.3s;display:inline-flex;align-items:center;gap:8px;}\n" +
                ".user-item:hover{transform:translateY(-2px);border-color:#1677ff;box-shadow:0 2px 8px rgba(22,119,255,0.1);}\n" +
                ".user-item .user-avatar{width:24px;height:24px;background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);border-radius:50%;display:inline-flex;align-items:center;justify-content:center;font-size:12px;color:white;}\n" +
                ".empty-users{color:#94a3b8;text-align:center;padding:32px;font-size:14px;}\n" +
                ".footer{text-align:center;padding:32px 0;color:#94a3b8;font-size:12px;border-top:1px solid #e8ecf1;margin-top:16px;}\n" +
                "@media(max-width:1024px){.stats-grid{grid-template-columns:repeat(3,1fr);}}\n" +
                "@media(max-width:768px){.nav-container{padding:0 20px;}.menu a{padding:6px 14px;font-size:12px;}.stats-grid{grid-template-columns:repeat(2,1fr);}.chart-row{grid-template-columns:1fr;}.page-wrap{padding:20px;}}\n" +
                "@media(max-width:480px){.stats-grid{grid-template-columns:1fr;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"navbar\">\n" +
                "<div class=\"nav-container\">\n" +
                "<div class=\"logo\">\n" +
                "<div class=\"logo-icon\">📊</div>\n" +
                "<div class=\"logo-text\">ECCOM WEB 监控管理系统</div>\n" +
                "<div class=\"logo-sub\">v3.0</div>\n" +
                "</div>\n" +
                "<div class=\"menu\">\n" +
                "<a href=\"/monitor/index\" class=\"active\">首页</a>\n" +
                "<a href=\"/monitor/dashboard\">服务监控</a>\n" +
                "<a href=\"/monitor/login-audit\">登录审计</a>\n" +
                "<a href=\"/monitor/number-files\">号码备份</a>\n" +
                "<a href=\"/monitor/customer-management\">API客户管理</a>\n" +
                "<a href=\"/monitor/user-management\">登录账户管理</a>\n" +
                "<a href=\"/monitor/ops-management\">运维管理</a>\n" +
                "<a href=\"/monitor/queue-management\">发送队列管理</a>" +
                "<a href=\"/monitor/queue-monitor\">队列DR监控</a>\n" +
                "</div>\n" +
                "<div class=\"user-info\">\n" +
                "<span class=\"user-name\" id=\"currentUsername\">加载中...</span>\n" +
                "<span class=\"user-role\" id=\"currentUserRole\">加载中...</span>\n" +
                "<button class=\"logout-btn\" onclick=\"logout()\">退出</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"page-wrap\">\n" +
                "<div class=\"welcome-card\">\n" +
                "<h1 id=\"welcomeTitle\">欢迎回来，加载中...</h1>\n" +
                "<p>欢迎使用ECCOM 监控管理系统</p>\n" +
                "<div class=\"welcome-stats\" id=\"welcomeStats\"></div>\n" +
                "</div>\n" +
                "<div class=\"stats-grid\" id=\"statsGrid\"></div>\n" +
                "<div class=\"section-title\">👥 今日活跃账号</div>\n" +
                "<div class=\"user-list-card\" id=\"userListCard\">\n" +
                "<div class=\"user-list-header\">\n" +
                "<h3><span>👥</span> 今日登录账号列表</h3>\n" +
                "<span class=\"user-badge\" id=\"userCountBadge\">加载中...</span>\n" +
                "</div>\n" +
                "<div class=\"user-items\" id=\"userItems\">\n" +
                "<div class=\"empty-users\">加载中...</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"section-title\">📈 数据分析</div>\n" +
                "<div class=\"chart-row\">\n" +
                "<div class=\"chart-card\"><h3>📊 今日登录趋势（按小时）</h3><canvas id=\"hourlyChart\" width=\"400\" height=\"200\"></canvas></div>\n" +
                "<div class=\"chart-card\"><h3>🥇 失败IP Top 5</h3><canvas id=\"failIpChart\" width=\"400\" height=\"200\"></canvas></div>\n" +
                "</div>\n" +
                "<div class=\"footer\">TexCell SMS 监控管理系统 | 数据实时更新 | 仅限内部使用</div>\n" +
                "</div>\n" +
                "<script>\n" +
                "function logout(){fetch('/monitor/logout').then(function(){location.href='/monitor/login';});}\n" +
                "// 获取当前登录用户信息\n" +
                "function getCurrentUserInfo() {\n" +
                "    fetch('/api/monitor/current-user?secret=dn17ORUwf36Znl')\n" +
                "        .then(function(res) { return res.json(); })\n" +
                "        .then(function(data) {\n" +
                "            if (data.code === 200) {\n" +
                "                document.getElementById('currentUsername').innerText = data.data.username;\n" +
                "                var roleSpan = document.getElementById('currentUserRole');\n" +
                "                var roleText = data.data.role === 'ADMIN' ? '👑 管理员' : '🔧 NOC';\n" +
                "                roleSpan.innerText = roleText;\n" +
                "                var welcomeTitle = document.getElementById('welcomeTitle');\n" +
                "                if (welcomeTitle) welcomeTitle.innerText = '欢迎回来，' + data.data.username;\n" +
                "            }\n" +
                "        }).catch(function(err) { console.error('获取用户信息失败:', err); });\n" +
                "}\n" +
                "getCurrentUserInfo();\n" +
                "let hourlyChart, failIpChart;\n" +
                "function renderActiveUsers(users) {\n" +
                "  var container = document.getElementById('userItems');\n" +
                "  var badge = document.getElementById('userCountBadge');\n" +
                "  if (!users || users.length === 0) {\n" +
                "    container.innerHTML = '<div class=\"empty-users\">暂无活跃账号</div>';\n" +
                "    badge.innerHTML = '0 个账号';\n" +
                "    return;\n" +
                "  }\n" +
                "  badge.innerHTML = users.length + ' 个账号';\n" +
                "  var colors = ['#667eea', '#764ba2', '#f59e0b', '#10b981', '#e53935', '#06b6d4', '#8b5cf6', '#ec4899'];\n" +
                "  var html = '';\n" +
                "  users.forEach(function(user, index) {\n" +
                "    var color = colors[index % colors.length];\n" +
                "    var initial = user.charAt(0).toUpperCase();\n" +
                "    html += '<div class=\"user-item\">' +\n" +
                "      '<div class=\"user-avatar\" style=\"background:' + color + '\">' + initial + '</div>' +\n" +
                "      '<span>' + escapeHtml(user) + '</span>' +\n" +
                "      '</div>';\n" +
                "  });\n" +
                "  container.innerHTML = html;\n" +
                "}\n" +
                "function escapeHtml(s) { if (!s) return ''; return s.replace(/[&<>]/g, function(m) { if (m === '&') return '&amp;'; if (m === '<') return '&lt;'; if (m === '>') return '&gt;'; return m; }); }\n" +
                "fetch('/api/monitor/login-stats?secret=dn17ORUwf36Znl').then(function(res){return res.json();}).then(function(data){if(data.code===200&&data.data){var t=data.data.today||{},w=data.data.week||{},m=data.data.month||{},two=data.data.twoFactorStats||[];var google=0,email=0;for(var i=0;i<two.length;i++){var s=two[i];if(s.twoFactorType==='GOOGLE')google=s.totalCount||0;if(s.twoFactorType==='EMAIL')email=s.totalCount||0;}var successRate=t.total>0?(t.success*100/t.total).toFixed(1):0;document.getElementById('statsGrid').innerHTML='<div class=\"stat-card\"><div class=\"stat-icon\">📅</div><div class=\"stat-number\">'+(t.total||0)+'</div><div class=\"stat-label\">今日登录</div><div class=\"stat-trend\">✅成功 '+(t.success||0)+' / ❌失败 '+(t.fail||0)+'</div></div><div class=\"stat-card\"><div class=\"stat-icon\">📆</div><div class=\"stat-number\">'+(w.total||0)+'</div><div class=\"stat-label\">本周登录</div><div class=\"stat-trend\">📈 较上周 '+(w.total>m.total?'↑':'↓')+Math.abs(w.total-m.total)+'</div></div><div class=\"stat-card\"><div class=\"stat-icon\">📊</div><div class=\"stat-number\">'+(m.total||0)+'</div><div class=\"stat-label\">本月登录</div></div><div class=\"stat-card\"><div class=\"stat-icon\">🎯</div><div class=\"stat-number\">'+successRate+'%</div><div class=\"stat-label\">今日成功率</div></div><div class=\"stat-card\"><div class=\"stat-icon\">👥</div><div class=\"stat-number\">'+(data.data.todayActiveUsers||0)+'</div><div class=\"stat-label\">今日活跃账号</div></div>';document.getElementById('welcomeStats').innerHTML='<div class=\"welcome-stat\">🕐 服务运行: '+(data.runDuration||'计算中')+'</div>';\n" +
                "  renderActiveUsers(data.data.todayActiveUserList || []);\n" +
                "  if(data.data.hourlyTrend&&data.data.hourlyTrend.length>0){var hours=[],totals=[];for(var j=0;j<data.data.hourlyTrend.length;j++){var item=data.data.hourlyTrend[j];hours.push(item.hour+':00');totals.push(item.totalCount||0);}if(hourlyChart)hourlyChart.destroy();var ctx=document.getElementById('hourlyChart').getContext('2d');hourlyChart=new Chart(ctx,{type:'line',data:{labels:hours,datasets:[{label:'登录次数',data:totals,borderColor:'#667eea',backgroundColor:'rgba(102,126,234,0.08)',fill:true,tension:0.3,borderWidth:2,pointRadius:3,pointBackgroundColor:'#667eea'}]},options:{responsive:true,maintainAspectRatio:true,plugins:{legend:{position:'top'}}}});}if(data.data.todayTopFailIps&&data.data.todayTopFailIps.length>0){var ips=[],counts=[];for(var k=0;k<data.data.todayTopFailIps.length;k++){var item=data.data.todayTopFailIps[k];ips.push(item.realIp);counts.push(item.failCount||0);}if(failIpChart)failIpChart.destroy();var ctx2=document.getElementById('failIpChart').getContext('2d');failIpChart=new Chart(ctx2,{type:'bar',data:{labels:ips,datasets:[{label:'失败次数',data:counts,backgroundColor:'#e53935',borderRadius:8,barPercentage:0.7}]},options:{responsive:true,maintainAspectRatio:true,plugins:{legend:{position:'top'}}}});}}}).catch(function(err){console.error(err);});\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }

    private String generateDashboardHtml() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=no\">\n" +
                "<title>Texcell ECCOM WEB 管理面板</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='20' fill='%23667eea'/%3E%3Ctext x='50' y='68' font-size='50' text-anchor='middle' fill='white' font-family='Arial'%3E📊%3C/text%3E%3C/svg%3E\">\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap\" rel=\"stylesheet\">\n" +
                "<style>\n" +
                ":root {\n" +
                "  --bg-primary: #f5f7fa;\n" +
                "  --bg-card: #ffffff;\n" +
                "  --text-primary: #1e293b;\n" +
                "  --text-secondary: #64748b;\n" +
                "  --text-muted: #94a3b8;\n" +
                "  --border-color: #e8ecf1;\n" +
                "  --shadow: 0 1px 3px rgba(0,0,0,0.06);\n" +
                "  --navbar-bg: #ffffff;\n" +
                "  --hover-bg: rgba(0,0,0,0.02);\n" +
                "  --primary-color: #1677ff;\n" +
                "  --primary-hover: #4096ff;\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:#f5f7fa;font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;}\n" +
                ".page-wrap{max-width:2000px;margin:0 auto;padding:32px;}\n" +
                ".navbar{background:#ffffff;box-shadow:0 1px 3px rgba(0,0,0,0.06);border:1px solid #e8ecf1;border-radius:12px;margin-bottom:24px;}\n" +
                ".nav-container{max-width:2000px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;padding:0 32px;height:70px;}\n" +
                ".logo{display:flex;align-items:center;gap:12px;}\n" +
                ".logo-icon{width:40px;height:40px;background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);border-radius:12px;display:flex;align-items:center;justify-content:center;font-size:20px;}\n" +
                ".logo-text{font-size:18px;font-weight:700;color:#1e293b;letter-spacing:1px;}\n" +
                ".logo-sub{font-size:11px;color:#94a3b8;margin-left:8px;}\n" +
                ".menu{display:flex;gap:12px;}\n" +
                ".menu a{color:#64748b;text-decoration:none;padding:8px 20px;border-radius:40px;transition:all 0.3s;font-weight:500;font-size:14px;}\n" +
                ".menu a:hover{background:#f1f5f9;color:#1e293b;}\n" +
                ".menu a.active{background:#e8f0fe;color:#1677ff;box-shadow:none;}\n" +
                ".user-info{display:flex;align-items:center;gap:20px;}\n" +
                ".user-name{color:#475569;font-size:14px;}\n" +
                ".user-role{background:rgba(22,119,255,0.1);padding:4px 12px;border-radius:20px;font-size:12px;color:#1677ff;}\n" +
                ".logout-btn{background:#f1f5f9;border:1px solid #e8ecf1;color:#475569;padding:8px 20px;border-radius:40px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".status-header{background:#ffffff;border-radius:12px;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);padding:24px 32px;margin-bottom:28px;display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:16px;}\n" +
                ".status-info{display:flex;gap:32px;align-items:center;flex-wrap:wrap;}\n" +
                ".status-indicator{display:flex;align-items:center;gap:10px;}\n" +
                ".status-led{width:14px;height:14px;border-radius:50%;background-color:#52c41a;box-shadow:0 0 12px rgba(82,196,26,0.3);animation:pulse 2s infinite;}\n" +
                "@keyframes pulse{0%,100%{opacity:1;}50%{opacity:0.5;}}\n" +
                ".status-text{font-size:15px;font-weight:500;color:#1e293b;}\n" +
                ".time-info{display:flex;gap:28px;font-size:13px;color:#64748b;}\n" +
                ".refresh-btn{background:#f1f5f9;border:1px solid #e8ecf1;color:#475569;padding:8px 20px;border-radius:40px;cursor:pointer;font-size:13px;transition:all 0.3s;}\n" +
                ".refresh-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".metrics-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:24px;margin-bottom:32px;}\n" +
                ".metric-card{background:#ffffff;border-radius:12px;padding:24px;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);transition:all 0.3s;}\n" +
                ".metric-card:hover{transform:translateY(-2px);box-shadow:0 4px 12px rgba(0,0,0,0.06);border-color:#1677ff;}\n" +
                ".metric-title{font-size:14px;color:#94a3b8;margin-bottom:10px;display:flex;align-items:center;gap:8px;font-weight:500;}\n" +
                ".metric-value{font-size:34px;font-weight:800;color:#1e293b;}\n" +
                ".metric-unit{font-size:14px;font-weight:400;color:#94a3b8;margin-left:4px;}\n" +
                ".metric-sub{font-size:12px;color:#94a3b8;margin-top:10px;padding-top:8px;border-top:1px solid #e8ecf1;}\n" +
                ".module-section{margin-bottom:40px;}\n" +
                ".section-title{font-size:18px;font-weight:700;color:#1e293b;margin-bottom:16px;display:flex;align-items:center;gap:8px;}\n" +
                ".section-title::before{content:'';width:4px;height:20px;background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);border-radius:4px;}\n" +
                ".log-card{background:#ffffff;border-radius:12px;margin-bottom:32px;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);overflow:hidden;}\n" +
                ".log-card:last-child{margin-bottom:0;}\n" +
                ".log-header{padding:18px 24px;border-bottom:1px solid #e8ecf1;background:#f8fafc;font-weight:600;font-size:16px;color:#1e293b;display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:12px;}\n" +
                ".log-badge{background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);color:white;border-radius:20px;padding:4px 12px;font-size:11px;font-weight:500;}\n" +
                ".log-badge-attack{background:#ff4d4f;color:white;border-radius:20px;padding:4px 12px;font-size:11px;font-weight:500;}\n" +
                ".table-wrapper{overflow-x:auto;}\n" +
                ".log-table{width:100%;border-collapse:collapse;}\n" +
                ".log-table th{background:#f8fafc;padding:14px 16px;text-align:left;font-size:13px;font-weight:600;color:#64748b;border-bottom:1px solid #e8ecf1;}\n" +
                ".log-table td{padding:14px 16px;font-size:13px;color:#475569;border-bottom:1px solid #e8ecf1;}\n" +
                ".log-table tr:hover td{background:#f8fafc;}\n" +
                ".success{color:#52c41a;font-weight:600;}.fail{color:#ff4d4f;font-weight:600;}\n" +
                ".gc-warn{color:#faad14;}.gc-error{color:#ff4d4f;}\n" +
                ".attack-critical{color:#ff4d4f;font-weight:600;}\n" +
                ".empty{text-align:center;padding:48px;color:#94a3b8;}\n" +
                ".pagination{display:flex;justify-content:center;gap:12px;padding:16px 20px;border-top:1px solid #e8ecf1;background:#f8fafc;flex-wrap:wrap;}\n" +
                ".page-btn{padding:8px 16px;border:1px solid #e8ecf1;background:#ffffff;color:#1e293b;border-radius:40px;cursor:pointer;font-size:13px;transition:all 0.3s;font-weight:500;}\n" +
                ".page-btn:hover:not(:disabled){border-color:#1677ff;color:#1677ff;transform:translateY(-1px);}\n" +
                ".page-btn:disabled{opacity:0.5;cursor:not-allowed;background:#f1f5f9;}\n" +
                ".page-info{font-size:13px;color:#94a3b8;}\n" +
                ".footer{text-align:center;padding:32px;color:#94a3b8;font-size:12px;border-top:1px solid #e8ecf1;margin-top:40px;}\n" +
                "@media(max-width:768px){.metrics-grid{grid-template-columns:repeat(2,1fr);}.status-info{flex-direction:column;align-items:flex-start;}.time-info{flex-direction:column;gap:8px;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"page-wrap\">\n" +
                "<div class=\"navbar\">\n" +
                "<div class=\"nav-container\">\n" +
                "<div class=\"logo\">\n" +
                "<div class=\"logo-icon\">📊</div>\n" +
                "<div class=\"logo-text\">ECCOM WEB 监控管理系统</div>\n" +
                "<div class=\"logo-sub\">v3.0</div>\n" +
                "</div>\n" +
                "<div class=\"menu\">\n" +
                "<a href=\"/monitor/index\">首页</a>\n" +
                "<a href=\"/monitor/dashboard\" class=\"active\">服务监控</a>\n" +
                "<a href=\"/monitor/login-audit\">登录审计</a>\n" +
                "<a href=\"/monitor/number-files\">号码备份</a>\n" +
                "<a href=\"/monitor/customer-management\">API客户管理</a>\n" +
                "<a href=\"/monitor/user-management\">登录账户管理</a>\n" +
                "<a href=\"/monitor/ops-management\">运维管理</a>\n" +
                "<a href=\"/monitor/queue-management\">发送队列管理</a>\n" +
                "<a href=\"/monitor/queue-monitor\">队列DR监控</a>\n" +
                "</div>\n" +
                "<div class=\"user-info\">\n" +
                "<span class=\"user-name\" id=\"currentUsername\">加载中...</span>\n" +
                "<span class=\"user-role\" id=\"currentUserRole\">加载中...</span>\n" +
                "<button class=\"logout-btn\" onclick=\"logout()\">退出</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"status-header\">\n" +
                "<div class=\"status-info\">\n" +
                "<div class=\"status-indicator\"><div class=\"status-led\" id=\"statusLed\"></div><span class=\"status-text\" id=\"statusText\">服务运行中</span></div>\n" +
                "<div class=\"time-info\"><span>🚀 启动时间: <span id=\"startTime\">-</span></span><span>⏱️ 已运行: <span id=\"runDuration\">-</span></span><span>🖥️ 当前IP: <span id=\"currentIp\">-</span></span></div>\n" +
                "</div>\n" +
                "<button class=\"refresh-btn\" onclick=\"loadAllData()\">🔄 刷新数据</button>\n" +
                "</div>\n" +
                "<div class=\"metrics-grid\" id=\"metricsGrid\"></div>\n" +
                "<div class=\"module-section\"><div class=\"section-title\">🌐 API调用监控</div><div class=\"log-card\"><div class=\"log-header\"><span>📋 调用记录</span><span class=\"log-badge\">最近50条 | 每页10条</span></div><div class=\"table-wrapper\"><table class=\"log-table\"><thead><tr><th>时间</th><th>API路径</th><th>耗时(ms)</th><th>状态</th></tr></thead><tbody id=\"apiLogBody\"></tbody></table></div><div class=\"pagination\" id=\"apiPagination\"></div></div></div>\n" +
                "<div class=\"module-section\"><div class=\"section-title\">🛡️ 安全攻击监控</div><div class=\"log-card\"><div class=\"log-header\"><span>⚠️ 攻击日志</span><span class=\"log-badge-attack\">实时防护 | 每页10条</span></div><div class=\"table-wrapper\"><table class=\"log-table\"><thead><tr><th>时间</th><th>攻击IP</th><th>攻击类型</th><th>目标地址</th><th>详情</th></tr></thead><tbody id=\"attackLogBody\"></tbody></table></div><div class=\"pagination\" id=\"attackPagination\"></div></div></div>\n" +
                "<div class=\"module-section\"><div class=\"section-title\">📨 长短信发送记录</div><div class=\"log-card\"><div class=\"log-header\"><span>✉️ 发送日志</span><span class=\"log-badge\">最近20条 | 每页10条</span></div><div class=\"table-wrapper\"><table class=\"log-table\"><thead><tr><th>时间</th><th>发送记录</th></tr></thead><tbody id=\"longSmsBody\"></tbody></table></div><div class=\"pagination\" id=\"longSmsPagination\"></div></div></div>\n" +
                "<div class=\"module-section\"><div class=\"section-title\">💾 GC异常监控</div><div class=\"log-card\"><div class=\"log-header\"><span>🗑️ 垃圾回收日志</span><span class=\"log-badge\">需关注 | 每页10条</span></div><div class=\"table-wrapper\"><table class=\"log-table\"><thead><tr><th>时间</th><th>GC原始日志</th><th>关键说明</th></tr></thead><tbody id=\"gcLogBody\"></tbody></table></div><div class=\"pagination\" id=\"gcPagination\"></div></div></div>\n" +
                "<div class=\"module-section\"><div class=\"section-title\">⚠️ 业务异常监控</div><div class=\"log-card\"><div class=\"log-header\"><span>🐛 异常日志</span><span class=\"log-badge\">最近20条 | 每页10条</span></div><div class=\"table-wrapper\"><table class=\"log-table\"><thead><tr><th>时间</th><th>异常类型</th><th>说明</th></tr></thead><tbody id=\"errorLogBody\"></tbody></table></div><div class=\"pagination\" id=\"errorPagination\"></div></div></div>\n" +
                "<div class=\"footer\">TexCell SMS 监控管理系统 | 数据实时更新 | 每30秒自动刷新</div>\n" +
                "</div>\n" +
                "<script>\n" +
                "function logout(){fetch('/monitor/logout').then(function(){location.href='/monitor/login';});}\n" +
                "// 获取当前登录用户信息\n" +
                "function getCurrentUserInfo() {\n" +
                "    fetch('/api/monitor/current-user?secret=dn17ORUwf36Znl')\n" +
                "        .then(function(res) { return res.json(); })\n" +
                "        .then(function(data) {\n" +
                "            if (data.code === 200) {\n" +
                "                document.getElementById('currentUsername').innerText = data.data.username;\n" +
                "                var roleSpan = document.getElementById('currentUserRole');\n" +
                "                var roleText = data.data.role === 'ADMIN' ? '👑 管理员' : '🔧 NOC';\n" +
                "                roleSpan.innerText = roleText;\n" +
                "            }\n" +
                "        }).catch(function(err) { console.error('获取用户信息失败:', err); });\n" +
                "}\n" +
                "getCurrentUserInfo();\n" +
                "var PAGE_SIZE=10;\n" +
                "var apiData=[],attackData=[],longSmsData=[],gcData=[],errorData=[];\n" +
                "var apiPage=1,attackPage=1,longSmsPage=1,gcPage=1,errorPage=1;\n" +
                "var runInterval=null;\n" +
                "function escapeHtml(s){if(!s)return'';return s.replace(/[&<>]/g,function(m){if(m==='&')return'&amp;';if(m==='<')return'&lt;';if(m==='>')return'&gt;';return m;});}\n" +
                "function getGcNote(line){if(line.includes('Full GC'))return'【严重】Full GC';if(line.includes('OutOfMemory'))return'【致命】OOM';if(line.includes('Allocation Failure'))return'内存分配失败';if(line.includes('promotion failed'))return'晋升失败';if(line.includes('Metaspace OOM'))return'元空间溢出';return'GC异常';}\n" +
                "function formatNumber(num){return num!==undefined?num.toLocaleString():'0';}\n" +
                "function updateRunTime(startTimeStr){if(!startTimeStr||startTimeStr==='-')return;var start=new Date(startTimeStr);var now=new Date();var diff=Math.floor((now-start)/1000);var days=Math.floor(diff/86400);var hours=Math.floor((diff%86400)/3600);var mins=Math.floor((diff%3600)/60);var secs=diff%60;var text='';if(days>0)text+=days+'天';if(hours>0)text+=hours+'时';if(mins>0)text+=mins+'分';text+=secs+'秒';var runElem=document.getElementById('runDuration');if(runElem)runElem.innerText=text;}\n" +
                "function renderApiTable(){var container=document.getElementById('apiLogBody');if(!container)return;var start=(apiPage-1)*PAGE_SIZE;var end=start+PAGE_SIZE;var pageData=apiData.slice(start,end);var html='';if(pageData.length===0){html='<tr><td colspan=\"4\" class=\"empty\">暂无API调用记录</td></tr>';}else{for(var i=0;i<pageData.length;i++){var log=pageData[i];var parts=log.match(/\\[(.*?)\\] \\| (.*?) \\| (\\d+)ms \\| (.*)$/);if(parts){html+='<tr><td style=\"white-space:nowrap\">'+escapeHtml(parts[1])+'</td><td style=\"font-family:monospace;word-break:break-all\">'+escapeHtml(parts[2])+'</td><td>'+parts[3]+'</td><td class=\"'+(parts[4]==='成功'?'success':'fail')+'\">'+parts[4]+'</td></tr>';}else{html+='<tr><td colspan=\"4\">'+escapeHtml(log)+'</td></tr>';}}}container.innerHTML=html;var totalPages=Math.ceil(apiData.length/PAGE_SIZE);var dom=document.getElementById('apiPagination');if(!dom)return;if(totalPages<=1){dom.innerHTML='';return;}dom.innerHTML='<button class=\"page-btn\" onclick=\"apiPage=1;renderApiTable();\"'+(apiPage===1?'disabled':'')+'>首页</button><button class=\"page-btn\" onclick=\"apiPage='+(apiPage-1)+';renderApiTable();\"'+(apiPage===1?'disabled':'')+'>上一页</button><span class=\"page-info\">第'+apiPage+'/'+totalPages+'页</span><button class=\"page-btn\" onclick=\"apiPage='+(apiPage+1)+';renderApiTable();\"'+(apiPage===totalPages?'disabled':'')+'>下一页</button><button class=\"page-btn\" onclick=\"apiPage='+totalPages+';renderApiTable();\"'+(apiPage===totalPages?'disabled':'')+'>尾页</button>';}\n" +
                "function renderAttackTable(){var container=document.getElementById('attackLogBody');if(!container)return;var start=(attackPage-1)*PAGE_SIZE;var end=start+PAGE_SIZE;var pageData=attackData.slice(start,end);var html='';if(pageData.length===0){html='<tr><td colspan=\"5\" class=\"empty\">暂无安全攻击</td></td>';}else{for(var i=0;i<pageData.length;i++){var attack=pageData[i];var parts=attack.split(' | ');if(parts.length>=4){var time=parts[0].replace(/[\\[\\]]/g,'');var ip=parts[1].replace('IP:','');var type=parts[2].replace('类型:','');var target=parts[3].replace('目标:','');var detail=parts[4]?parts[4].replace('详情:',''):'';html+='<tr><td style=\"white-space:nowrap\">'+time+'</td><td class=\"attack-critical\">'+ip+'</td><td class=\"attack-critical\">'+type+'</td><td>'+escapeHtml(target)+'</td><td>'+escapeHtml(detail)+'</td></tr>';}else{html+='<tr><td colspan=\"5\" class=\"attack-critical\">'+escapeHtml(attack)+'</td></tr>';}}}container.innerHTML=html;var totalPages=Math.ceil(attackData.length/PAGE_SIZE);var dom=document.getElementById('attackPagination');if(!dom)return;if(totalPages<=1){dom.innerHTML='';return;}dom.innerHTML='<button class=\"page-btn\" onclick=\"attackPage=1;renderAttackTable();\"'+(attackPage===1?'disabled':'')+'>首页</button><button class=\"page-btn\" onclick=\"attackPage='+(attackPage-1)+';renderAttackTable();\"'+(attackPage===1?'disabled':'')+'>上一页</button><span class=\"page-info\">第'+attackPage+'/'+totalPages+'页</span><button class=\"page-btn\" onclick=\"attackPage='+(attackPage+1)+';renderAttackTable();\"'+(attackPage===totalPages?'disabled':'')+'>下一页</button><button class=\"page-btn\" onclick=\"attackPage='+totalPages+';renderAttackTable();\"'+(attackPage===totalPages?'disabled':'')+'>尾页</button>';}\n" +
                "function renderLongSmsTable(){var container=document.getElementById('longSmsBody');if(!container)return;var start=(longSmsPage-1)*PAGE_SIZE;var end=start+PAGE_SIZE;var pageData=longSmsData.slice(start,end);var html='';if(pageData.length===0){html='<tr><td colspan=\"2\" class=\"empty\">暂无长短信记录</td></td>';}else{for(var i=0;i<pageData.length;i++){var log=pageData[i];html+='<tr><td style=\"white-space:nowrap\">'+escapeHtml(log.substring(0,19))+'</td><td style=\"word-break:break-all\">'+escapeHtml(log)+'</td></tr>';}}container.innerHTML=html;var totalPages=Math.ceil(longSmsData.length/PAGE_SIZE);var dom=document.getElementById('longSmsPagination');if(!dom)return;if(totalPages<=1){dom.innerHTML='';return;}dom.innerHTML='<button class=\"page-btn\" onclick=\"longSmsPage=1;renderLongSmsTable();\"'+(longSmsPage===1?'disabled':'')+'>首页</button><button class=\"page-btn\" onclick=\"longSmsPage='+(longSmsPage-1)+';renderLongSmsTable();\"'+(longSmsPage===1?'disabled':'')+'>上一页</button><span class=\"page-info\">第'+longSmsPage+'/'+totalPages+'页</span><button class=\"page-btn\" onclick=\"longSmsPage='+(longSmsPage+1)+';renderLongSmsTable();\"'+(longSmsPage===totalPages?'disabled':'')+'>下一页</button><button class=\"page-btn\" onclick=\"longSmsPage='+totalPages+';renderLongSmsTable();\"'+(longSmsPage===totalPages?'disabled':'')+'>尾页</button>';}\n" +
                "function renderGcTable(){var container=document.getElementById('gcLogBody');if(!container)return;var start=(gcPage-1)*PAGE_SIZE;var end=start+PAGE_SIZE;var pageData=gcData.slice(start,end);var html='';if(pageData.length===0){html='<tr><td colspan=\"3\" class=\"empty\">✅ GC运行正常，无异常</td></tr>';}else{for(var i=0;i<pageData.length;i++){var log=pageData[i];var shortLog=log.length>100?log.substring(0,100)+'...':log;var note=getGcNote(log);html+='<tr><td style=\"white-space:nowrap\">'+log.substring(0,19)+'</td><td class=\"gc-warn\">'+escapeHtml(shortLog)+'</td><td class=\"gc-warn\">'+note+'</td></tr>';}}container.innerHTML=html;var totalPages=Math.ceil(gcData.length/PAGE_SIZE);var dom=document.getElementById('gcPagination');if(!dom)return;if(totalPages<=1){dom.innerHTML='';return;}dom.innerHTML='<button class=\"page-btn\" onclick=\"gcPage=1;renderGcTable();\"'+(gcPage===1?'disabled':'')+'>首页</button><button class=\"page-btn\" onclick=\"gcPage='+(gcPage-1)+';renderGcTable();\"'+(gcPage===1?'disabled':'')+'>上一页</button><span class=\"page-info\">第'+gcPage+'/'+totalPages+'页</span><button class=\"page-btn\" onclick=\"gcPage='+(gcPage+1)+';renderGcTable();\"'+(gcPage===totalPages?'disabled':'')+'>下一页</button><button class=\"page-btn\" onclick=\"gcPage='+totalPages+';renderGcTable();\"'+(gcPage===totalPages?'disabled':'')+'>尾页</button>';}\n" +
                "function renderErrorTable(){var container=document.getElementById('errorLogBody');if(!container)return;var start=(errorPage-1)*PAGE_SIZE;var end=start+PAGE_SIZE;var pageData=errorData.slice(start,end);var html='';if(pageData.length===0){html='</table><td colspan=\"3\" class=\"empty\">✅ 暂无异常</td></tr>';}else{for(var i=0;i<pageData.length;i++){var e=pageData[i];var parts=e.match(/\\[(.*?)\\] \\| \\[(.*?)\\] \\| (.*?) \\| (.*)$/);if(parts){html+='<tr><td style=\"white-space:nowrap\">'+parts[1]+'</td><td class=\"fail\">'+parts[2]+'</td><td>'+escapeHtml(parts[3].substring(0,80))+'</td></tr>';}else{html+='<tr><td colspan=\"3\">'+escapeHtml(e.substring(0,100))+'</td></tr>';}}}container.innerHTML=html;var totalPages=Math.ceil(errorData.length/PAGE_SIZE);var dom=document.getElementById('errorPagination');if(!dom)return;if(totalPages<=1){dom.innerHTML='';return;}dom.innerHTML='<button class=\"page-btn\" onclick=\"errorPage=1;renderErrorTable();\"'+(errorPage===1?'disabled':'')+'>首页</button><button class=\"page-btn\" onclick=\"errorPage='+(errorPage-1)+';renderErrorTable();\"'+(errorPage===1?'disabled':'')+'>上一页</button><span class=\"page-info\">第'+errorPage+'/'+totalPages+'页</span><button class=\"page-btn\" onclick=\"errorPage='+(errorPage+1)+';renderErrorTable();\"'+(errorPage===totalPages?'disabled':'')+'>下一页</button><button class=\"page-btn\" onclick=\"errorPage='+totalPages+';renderErrorTable();\"'+(errorPage===totalPages?'disabled':'')+'>尾页</button>';}\n" +
                "function loadAllData(){fetch('/api/monitor/logs?secret=dn17ORUwf36Znl').then(function(res){return res.json();}).then(function(data){if(data.systemMetrics){var m=data.systemMetrics;var metricsHtml='<div class=\"metric-card\"><div class=\"metric-title\">🧠 堆内存使用</div><div class=\"metric-value\">'+(m.heapUsed||0)+'<span class=\"metric-unit\">MB</span></div><div class=\"metric-sub\">最大: '+(m.heapMax||0)+' MB | 使用率: '+Math.round((m.heapUsed||0)/(m.heapMax||1)*100)+'%</div></div><div class=\"metric-card\"><div class=\"metric-title\">📦 非堆内存</div><div class=\"metric-value\">'+(m.nonHeapUsed||0)+'<span class=\"metric-unit\">MB</span></div><div class=\"metric-sub\">已加载类: '+formatNumber(m.loadedClasses)+' 个</div></div><div class=\"metric-card\"><div class=\"metric-title\">🧵 线程状态</div><div class=\"metric-value\">'+formatNumber(m.threadCount)+'<span class=\"metric-unit\">个</span></div><div class=\"metric-sub\">峰值: '+formatNumber(m.peakThreadCount)+' | 守护: '+formatNumber(m.daemonThreadCount)+'</div></div><div class=\"metric-card\"><div class=\"metric-title\">⚡ CPU使用率</div><div class=\"metric-value\">'+(m.cpuUsage||0)+'<span class=\"metric-unit\">%</span></div><div class=\"metric-sub\">CPU核心: '+formatNumber(m.availableProcessors)+' 核</div></div>';var metricsGrid=document.getElementById('metricsGrid');if(metricsGrid)metricsGrid.innerHTML=metricsHtml;}\n" +
                "var startTimeElem=document.getElementById('startTime');if(startTimeElem)startTimeElem.innerText=data.startTime||'-';\n" +
                "var statusLed=document.getElementById('statusLed');if(statusLed)statusLed.style.backgroundColor='#52c41a';\n" +
                "var currentIpElem=document.getElementById('currentIp');if(currentIpElem)currentIpElem.innerText=data.clientIp||'-';\n" +
                "apiData = data.recentApiLogs || [];\n" +
                "attackData = data.attackStatistics?.recentAttacks || [];\n" +
                "longSmsData = data.recentLongSms || [];\n" +
                "gcData = data.recentGcLogs || [];\n" +
                "errorData = data.recentErrors || [];\n" +
                "apiPage=1;attackPage=1;longSmsPage=1;gcPage=1;errorPage=1;\n" +
                "renderApiTable();renderAttackTable();renderLongSmsTable();renderGcTable();renderErrorTable();\n" +
                "if(runInterval)clearInterval(runInterval);if(data.startTime&&data.startTime!=='-'){updateRunTime(data.startTime);runInterval=setInterval(function(){updateRunTime(data.startTime);},1000);}}).catch(function(err){console.error('加载失败',err);});}\n" +
                "loadAllData();setInterval(loadAllData,30000);\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }

    private String generateLoginAuditHtml() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "<title>Texcell ECCOM WEB 登录审计日志</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='20' fill='%23667eea'/%3E%3Ctext x='50' y='68' font-size='50' text-anchor='middle' fill='white' font-family='Arial'%3E📊%3C/text%3E%3C/svg%3E\">\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap\" rel=\"stylesheet\">\n" +
                "<style>\n" +
                ":root {\n" +
                "  --bg-primary: #f5f7fa;\n" +
                "  --bg-card: #ffffff;\n" +
                "  --text-primary: #1e293b;\n" +
                "  --text-secondary: #64748b;\n" +
                "  --text-muted: #94a3b8;\n" +
                "  --border-color: #e8ecf1;\n" +
                "  --shadow: 0 1px 3px rgba(0,0,0,0.06);\n" +
                "  --navbar-bg: #ffffff;\n" +
                "  --search-card-bg: #ffffff;\n" +
                "  --table-header-bg: #f8fafc;\n" +
                "  --table-hover: #f8fafc;\n" +
                "  --input-bg: #ffffff;\n" +
                "  --input-border: #d1d5db;\n" +
                "  --btn-reset-bg: #f1f5f9;\n" +
                "  --btn-reset-hover: #e8ecf1;\n" +
                "  --badge-google-bg: #e8f0fe;\n" +
                "  --badge-email-bg: #e6f7e6;\n" +
                "  --primary-color: #1677ff;\n" +
                "  --primary-hover: #4096ff;\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:#f5f7fa;font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;min-height:100vh;}\n" +
                ".page-wrap{max-width:2000px;margin:0 auto;padding:20px 24px;}\n" +
                ".navbar{background:#ffffff;box-shadow:0 1px 3px rgba(0,0,0,0.06);border:1px solid #e8ecf1;border-radius:12px;margin-bottom:24px;}\n" +
                ".nav-container{max-width:2000px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;padding:0 32px;height:70px;}\n" +
                ".logo{display:flex;align-items:center;gap:12px;}\n" +
                ".logo-icon{width:40px;height:40px;background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);border-radius:12px;display:flex;align-items:center;justify-content:center;font-size:20px;}\n" +
                ".logo-text{font-size:18px;font-weight:700;color:#1e293b;letter-spacing:1px;}\n" +
                ".logo-sub{font-size:11px;color:#94a3b8;margin-left:8px;}\n" +
                ".menu{display:flex;gap:12px;}\n" +
                ".menu a{color:#64748b;text-decoration:none;padding:8px 20px;border-radius:40px;transition:all 0.3s;font-weight:500;font-size:14px;}\n" +
                ".menu a:hover{background:#f1f5f9;color:#1e293b;}\n" +
                ".menu a.active{background:#e8f0fe;color:#1677ff;box-shadow:none;}\n" +
                ".user-info{display:flex;align-items:center;gap:20px;}\n" +
                ".user-name{color:#475569;font-size:14px;}\n" +
                ".user-role{background:rgba(22,119,255,0.1);padding:4px 12px;border-radius:20px;font-size:12px;color:#1677ff;}\n" +
                ".logout-btn{background:#f1f5f9;border:1px solid #e8ecf1;color:#475569;padding:8px 20px;border-radius:40px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".stats-grid{display:grid;grid-template-columns:repeat(5,1fr);gap:16px;margin-bottom:24px;}\n" +
                ".stat-card{background:#ffffff;border-radius:12px;padding:20px;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);border-left:4px solid #1677ff;transition:all 0.3s;}\n" +
                ".stat-card:hover{transform:translateY(-2px);box-shadow:0 4px 12px rgba(0,0,0,0.06);}\n" +
                ".stat-number{font-size:32px;font-weight:700;color:#1677ff;}\n" +
                ".stat-label{font-size:12px;color:#94a3b8;margin-top:6px;}\n" +
                ".stat-sub{font-size:11px;color:#94a3b8;margin-top:2px;}\n" +
                ".search-card{background:#ffffff;border-radius:12px;padding:20px 24px;margin-bottom:24px;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);}\n" +
                ".search-row{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-end;margin-bottom:16px;}\n" +
                ".search-row:last-child{margin-bottom:0;}\n" +
                ".search-group{display:flex;flex-direction:column;gap:6px;}\n" +
                ".search-group label{font-size:12px;color:#64748b;font-weight:500;}\n" +
                ".search-group input,.search-group select{padding:8px 12px;border:1px solid #d1d5db;border-radius:8px;font-size:13px;min-width:140px;transition:all 0.3s;background:#ffffff;color:#1e293b;}\n" +
                ".search-group input:focus,.search-group select:focus{outline:none;border-color:#1677ff;box-shadow:0 0 0 3px rgba(22,119,255,0.1);}\n" +
                ".search-group input[type=\"datetime-local\"]{min-width:180px;}\n" +
                ".search-actions{flex-direction:row;align-items:center;gap:10px;margin-left:auto;}\n" +
                ".search-btn{background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);color:white;border:none;padding:8px 20px;border-radius:8px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".search-btn:hover{transform:translateY(-1px);box-shadow:0 4px 12px rgba(22,119,255,0.3);}\n" +
                ".reset-btn{background:#f1f5f9;border:1px solid #d1d5db;padding:8px 20px;border-radius:8px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;color:#64748b;}\n" +
                ".reset-btn:hover{background:#e8ecf1;}\n" +
                ".export-btn{background:linear-gradient(135deg,#52c41a 0%,#389e0d 100%);color:white;border:none;padding:8px 20px;border-radius:8px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".export-btn:hover{transform:translateY(-1px);box-shadow:0 4px 12px rgba(82,196,26,0.3);}\n" +
                ".log-card{background:#ffffff;border-radius:12px;overflow:auto;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);}\n" +
                ".log-table{width:100%;border-collapse:collapse;}\n" +
                ".log-table th{background:#f8fafc;padding:14px 16px;text-align:left;font-size:13px;font-weight:600;color:#64748b;border-bottom:1px solid #e8ecf1;}\n" +
                ".log-table td{padding:14px 16px;font-size:13px;color:#1e293b;border-bottom:1px solid #e8ecf1;}\n" +
                ".log-table tr:hover td{background:#f8fafc;cursor:pointer;}\n" +
                ".success{color:#52c41a;font-weight:500;}.fail{color:#ff4d4f;font-weight:500;}\n" +
                ".pagination{display:flex;justify-content:center;gap:12px;padding:24px;border-top:1px solid #e8ecf1;}\n" +
                ".page-btn{padding:8px 16px;border:1px solid #d1d5db;background:#ffffff;color:#1e293b;border-radius:8px;cursor:pointer;font-size:13px;transition:all 0.3s;}\n" +
                ".page-btn:hover:not(:disabled){border-color:#1677ff;color:#1677ff;}\n" +
                ".page-btn:disabled{background:#f1f5f9;color:#94a3b8;cursor:not-allowed;}\n" +
                ".page-info{font-size:13px;color:#94a3b8;align-self:center;}\n" +
                ".badge{display:inline-block;padding:2px 8px;border-radius:12px;font-size:11px;font-weight:500;}\n" +
                ".badge-google{background:#e8f0fe;color:#1677ff;}\n" +
                ".badge-email{background:#e6f7e6;color:#52c41a;}\n" +
                "@media(max-width:900px){.search-actions{margin-left:0;width:100%;justify-content:flex-start;}}\n" +
                "@media(max-width:768px){.stats-grid{grid-template-columns:repeat(2,1fr);}.search-group input,.search-group select,.search-group input[type=\"datetime-local\"]{min-width:100%;width:100%;}.search-actions{flex-wrap:wrap;}.navbar{padding:0 20px;}.nav-links a{padding:6px 12px;font-size:12px;}}\n" +
                "@media(max-width:480px){.stats-grid{grid-template-columns:1fr;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"page-wrap\">\n" +
                "<div class=\"navbar\">\n" +
                "<div class=\"nav-container\">\n" +
                "<div class=\"logo\">\n" +
                "<div class=\"logo-icon\">📊</div>\n" +
                "<div class=\"logo-text\">ECCOM WEB 监控管理系统</div>\n" +
                "<div class=\"logo-sub\">v3.0</div>\n" +
                "</div>\n" +
                "<div class=\"menu\">\n" +
                "<a href=\"/monitor/index\">首页</a>\n" +
                "<a href=\"/monitor/dashboard\">服务监控</a>\n" +
                "<a href=\"/monitor/login-audit\" class=\"active\">登录审计</a>\n" +
                "<a href=\"/monitor/number-files\">号码备份</a>\n" +
                "<a href=\"/monitor/customer-management\">API客户管理</a>\n" +
                "<a href=\"/monitor/user-management\">登录账户管理</a>\n" +
                "<a href=\"/monitor/ops-management\">运维管理</a>\n" +
                "<a href=\"/monitor/queue-management\">发送队列管理</a>\n" +
                "<a href=\"/monitor/queue-monitor\">队列DR监控</a>\n" +
                "</div>\n" +
                "<div class=\"user-info\">\n" +
                "<span class=\"user-name\" id=\"currentUsername\">加载中...</span>\n" +
                "<span class=\"user-role\" id=\"currentUserRole\">加载中...</span>\n" +
                "<button class=\"logout-btn\" onclick=\"logout()\">退出</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"stats-grid\" id=\"statsGrid\"></div>\n" +
                "<div class=\"search-card\">\n" +
                "<div class=\"search-row\">\n" +
                "<div class=\"search-group\"><label>👤 账号</label><input type=\"text\" id=\"searchUsername\" placeholder=\"模糊查询\"></div>\n" +
                "<div class=\"search-group\"><label>🌐 IP地址</label><input type=\"text\" id=\"searchIp\" placeholder=\"模糊查询\"></div>\n" +
                "<div class=\"search-group\"><label>📊 结果</label><select id=\"searchResult\"><option value=\"\">全部</option><option value=\"1\">成功</option><option value=\"0\">失败</option></select></div>\n" +
                "<div class=\"search-group\"><label>🔐 2FA类型</label><select id=\"searchTwoFactor\"><option value=\"\">全部</option><option value=\"GOOGLE\">Google验证器</option><option value=\"EMAIL\">邮箱验证</option></select></div>\n" +
                "</div>\n" +
                "<div class=\"search-row\">\n" +
                "<div class=\"search-group\"><label>📅 开始时间</label><input type=\"datetime-local\" id=\"startTime\"></div>\n" +
                "<div class=\"search-group\"><label>📅 结束时间</label><input type=\"datetime-local\" id=\"endTime\"></div>\n" +
                "<div class=\"search-group search-actions\"><button class=\"search-btn\" onclick=\"searchLogs()\">🔍 查询</button><button class=\"reset-btn\" onclick=\"resetSearch()\">🔄 重置</button><button class=\"export-btn\" onclick=\"exportLogs()\">📥 导出CSV</button></div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"log-card\">\n" +
                "<table class=\"log-table\"><thead><th>时间</th><th>账号</th><th>IP</th><th>地理位置</th><th>结果</th><th>结果码</th><th>2FA类型</th><th>耗时(ms)</th></thead><tbody id=\"logBody\"><tr><td colspan=\"8\" style=\"text-align:center;padding:48px;\">加载中...</td></tr>\n" +
                "</tbody>\n" +
                "</table>\n" +
                "<div class=\"pagination\" id=\"pagination\"></div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<script>\n" +
                "function logout(){fetch('/monitor/logout').then(function(){location.href='/monitor/login';});}\n" +
                "let page=1,size=20,totalPages=1;\n" +
                "// 获取当前登录用户信息\n" +
                "function getCurrentUserInfo() {\n" +
                "    fetch('/api/monitor/current-user?secret=dn17ORUwf36Znl')\n" +
                "        .then(function(res) { return res.json(); })\n" +
                "        .then(function(data) {\n" +
                "            if (data.code === 200) {\n" +
                "                document.getElementById('currentUsername').innerText = data.data.username;\n" +
                "                var roleSpan = document.getElementById('currentUserRole');\n" +
                "                var roleText = data.data.role === 'ADMIN' ? '👑 管理员' : '🔧 NOC';\n" +
                "                roleSpan.innerText = roleText;\n" +
                "            }\n" +
                "        }).catch(function(err) { console.error('获取用户信息失败:', err); });\n" +
                "}\n" +
                "getCurrentUserInfo();\n" +
                "function formatTwoFactor(tf){if(tf==='GOOGLE')return'<span class=\"badge badge-google\">🔐 Google</span>';if(tf==='EMAIL')return'<span class=\"badge badge-email\">📧 邮箱</span>';return'-';}\n" +
                "function loadStats(){fetch('/api/monitor/login-stats?secret=dn17ORUwf36Znl').then(res=>res.json()).then(data=>{if(data.code===200&&data.data){var t=data.data.today||{},w=data.data.week||{},m=data.data.month||{},two=data.data.twoFactorStats||[];var google=0,email=0;if(two && two.forEach){two.forEach(s=>{if(s.twoFactorType==='GOOGLE')google=s.totalCount||0;if(s.twoFactorType==='EMAIL')email=s.totalCount||0;});}var successRate=t.total>0?((t.success*100)/t.total).toFixed(1):0;var statsGrid = document.getElementById('statsGrid');if(statsGrid) statsGrid.innerHTML='<div class=\"stat-card\"><div class=\"stat-number\">'+(t.total||0)+'</div><div class=\"stat-label\">今日登录</div><div class=\"stat-sub\">✅成功 '+(t.success||0)+' / ❌失败 '+(t.fail||0)+'</div></div><div class=\"stat-card\"><div class=\"stat-number\">'+(w.total||0)+'</div><div class=\"stat-label\">本周登录</div></div><div class=\"stat-card\"><div class=\"stat-number\">'+(m.total||0)+'</div><div class=\"stat-label\">本月登录</div></div><div class=\"stat-card\"><div class=\"stat-number\">'+successRate+'%</div><div class=\"stat-label\">今日成功率</div></div><div class=\"stat-card\"><div class=\"stat-number\">'+google+'/'+email+'</div><div class=\"stat-label\">Google/邮箱2FA</div></div>';}}).catch(err=>console.error('加载统计数据失败:',err));}\n" +
                "function searchLogs(){page=1;loadLogs();}\n" +
                "function resetSearch(){var usernameInput = document.getElementById('searchUsername');if(usernameInput) usernameInput.value='';\n" +
                "var ipInput = document.getElementById('searchIp');if(ipInput) ipInput.value='';\n" +
                "var resultSelect = document.getElementById('searchResult');if(resultSelect) resultSelect.value='';\n" +
                "var twoFactorSelect = document.getElementById('searchTwoFactor');if(twoFactorSelect) twoFactorSelect.value='';\n" +
                "var startTimeInput = document.getElementById('startTime');if(startTimeInput) startTimeInput.value='';\n" +
                "var endTimeInput = document.getElementById('endTime');if(endTimeInput) endTimeInput.value='';\n" +
                "page=1;loadLogs();}\n" +
                "function loadLogs(){var u=document.getElementById('searchUsername') ? document.getElementById('searchUsername').value : '';\n" +
                "var ip=document.getElementById('searchIp') ? document.getElementById('searchIp').value : '';\n" +
                "var r=document.getElementById('searchResult') ? document.getElementById('searchResult').value : '';\n" +
                "var tf=document.getElementById('searchTwoFactor') ? document.getElementById('searchTwoFactor').value : '';\n" +
                "var st=document.getElementById('startTime') ? document.getElementById('startTime').value : '';\n" +
                "var et=document.getElementById('endTime') ? document.getElementById('endTime').value : '';\n" +
                "var url='/api/monitor/login-audit?secret=dn17ORUwf36Znl&page='+page+'&size='+size;if(u)url+='&username='+encodeURIComponent(u);if(ip)url+='&ip='+encodeURIComponent(ip);if(r)url+='&result='+r;if(tf)url+='&twoFactorType='+tf;if(st&&st.trim()!==''){var startTimeStr=st.replace('T',' ');if(startTimeStr.length===16)startTimeStr+=':00';url+='&startTime='+encodeURIComponent(startTimeStr);}if(et&&et.trim()!==''){var endTimeStr=et.replace('T',' ');if(endTimeStr.length===16)endTimeStr+=':00';url+='&endTime='+encodeURIComponent(endTimeStr);}fetch(url).then(res=>res.json()).then(data=>{if(data.code===200){renderTable(data.data||[]);totalPages=data.totalPages||1;renderPagination();}else{var logBody = document.getElementById('logBody');if(logBody) logBody.innerHTML='<tr><td colspan=\"8\" style=\"text-align:center;padding:48px;\">加载失败:'+(data.msg||'错误')+'</td></tr>';}}).catch(function(err){console.error('加载日志失败:',err);var logBody = document.getElementById('logBody');if(logBody) logBody.innerHTML='<tr><td colspan=\"8\" style=\"text-align:center;padding:48px;\">加载失败:网络错误</td></tr>';});}\n" +
                "function renderTable(logs){var logBody = document.getElementById('logBody');if(!logBody) return;if(!logs||logs.length===0){logBody.innerHTML='<tr><td colspan=\"8\" style=\"text-align:center;padding:48px;\">暂无数据</td></tr>';return;}var html='';for(var i=0;i<logs.length;i++){var l=logs[i];var loc=l.ipLocation||(l.ipCountry?l.ipCountry+(l.ipCity?l.ipCity:''):'-');html+='<tr ondblclick=\"showDetailDialog('+l.id+')\" style=\"cursor:pointer;\" title=\"双击查看详情\">'+\n" +
                "  '<td style=\"white-space:nowrap\">'+(l.loginTime||'')+'</td>'+\n" +
                "  '<td>'+escapeHtml(l.username||'')+'</td>'+\n" +
                "  '<td>'+(l.realIp||'')+'</td>'+\n" +
                "  '<td>'+escapeHtml(loc)+'</td>'+\n" +
                "  '<td class=\"'+(l.loginResult===1?'success':'fail')+'\">'+(l.loginResult===1?'成功':'失败')+'</td>'+\n" +
                "  '<td>'+(l.resultCode||'')+'</td>'+\n" +
                "  '<td>'+formatTwoFactor(l.twoFactorType)+'</td>'+\n" +
                "  '<td>'+(l.loginDuration||'-')+'</td>'+\n" +
                "  '</tr>';}logBody.innerHTML=html;}\n" +
                "function renderPagination(){var paginationDiv = document.getElementById('pagination');if(!paginationDiv) return;if(totalPages<=1){paginationDiv.innerHTML='';return;}var html='<button class=\"page-btn\" onclick=\"goPage(1)\"'+(page===1?'disabled':'')+'>首页</button><button class=\"page-btn\" onclick=\"goPage('+(page-1)+')\"'+(page===1?'disabled':'')+'>上一页</button><span class=\"page-info\">第 '+page+' / '+totalPages+' 页</span><button class=\"page-btn\" onclick=\"goPage('+(page+1)+')\"'+(page===totalPages?'disabled':'')+'>下一页</button><button class=\"page-btn\" onclick=\"goPage('+totalPages+')\"'+(page===totalPages?'disabled':'')+'>尾页</button>';paginationDiv.innerHTML=html;}\n" +
                "function goPage(p){page=p;loadLogs();}\n" +
                "function escapeHtml(s){if(!s)return'';return String(s).replace(/[&<>]/g,function(m){if(m==='&')return'&amp;';if(m==='<')return'&lt;';if(m==='>')return'&gt;';return m;});}\n" +
                "// 导出日志\n" +
                "function exportLogs() {\n" +
                "    var u = document.getElementById('searchUsername') ? document.getElementById('searchUsername').value : '';\n" +
                "    var ip = document.getElementById('searchIp') ? document.getElementById('searchIp').value : '';\n" +
                "    var r = document.getElementById('searchResult') ? document.getElementById('searchResult').value : '';\n" +
                "    var tf = document.getElementById('searchTwoFactor') ? document.getElementById('searchTwoFactor').value : '';\n" +
                "    var st = document.getElementById('startTime') ? document.getElementById('startTime').value : '';\n" +
                "    var et = document.getElementById('endTime') ? document.getElementById('endTime').value : '';\n" +
                "    var url = '/api/monitor/login-audit/export?secret=dn17ORUwf36Znl';\n" +
                "    if (u) url += '&username=' + encodeURIComponent(u);\n" +
                "    if (ip) url += '&ip=' + encodeURIComponent(ip);\n" +
                "    if (r) url += '&result=' + r;\n" +
                "    if (tf) url += '&twoFactorType=' + tf;\n" +
                "    if (st && st.trim() !== '') {\n" +
                "        var startTimeStr = st.replace('T', ' ');\n" +
                "        if (startTimeStr.length === 16) startTimeStr += ':00';\n" +
                "        url += '&startTime=' + encodeURIComponent(startTimeStr);\n" +
                "    }\n" +
                "    if (et && et.trim() !== '') {\n" +
                "        var endTimeStr = et.replace('T', ' ');\n" +
                "        if (endTimeStr.length === 16) endTimeStr += ':00';\n" +
                "        url += '&endTime=' + encodeURIComponent(endTimeStr);\n" +
                "    }\n" +
                "    window.location.href = url;\n" +
                "}\n" +
                "// 显示详情弹窗\n" +
                "function showDetailDialog(logId) {\n" +
                "    if (!logId) return;\n" +
                "    var modal = document.createElement('div');\n" +
                "    modal.id = 'detailModal';\n" +
                "    modal.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.6);z-index:10000;display:flex;align-items:center;justify-content:center;backdrop-filter:blur(4px);';\n" +
                "    modal.innerHTML = '<div style=\"background:#ffffff;border-radius:12px;max-width:550px;width:90%;max-height:80vh;overflow:hidden;display:flex;flex-direction:column;box-shadow:0 20px 60px rgba(0,0,0,0.15);\">' +\n" +
                "        '<div style=\"padding:20px 24px;border-bottom:1px solid #e8ecf1;display:flex;justify-content:space-between;align-items:center;\">' +\n" +
                "        '<h3 style=\"color:#1e293b;margin:0;display:flex;align-items:center;gap:8px;\"><span>📋</span> 登录详情</h3>' +\n" +
                "        '<button onclick=\"closeDetailModal()\" style=\"background:none;border:none;font-size:28px;cursor:pointer;color:#94a3b8;line-height:1;\">&times;</button>' +\n" +
                "        '</div>' +\n" +
                "        '<div style=\"padding:24px;overflow-y:auto;flex:1;\"><div style=\"text-align:center;padding:40px;\">加载中...</div></div>' +\n" +
                "        '</div>';\n" +
                "    document.body.appendChild(modal);\n" +
                "    fetch('/api/monitor/login-audit/detail?id=' + logId + '&secret=dn17ORUwf36Znl')\n" +
                "        .then(function(res){return res.json();})\n" +
                "        .then(function(data){\n" +
                "            if(data.code===200){\n" +
                "                renderDetailContent(data.data);\n" +
                "            }else{\n" +
                "                renderDetailError(data.msg||'加载失败');\n" +
                "            }\n" +
                "        })\n" +
                "        .catch(function(err){\n" +
                "            renderDetailError('网络错误: '+err.message);\n" +
                "        });\n" +
                "}\n" +
                "function renderDetailContent(detail) {\n" +
                "    var container = document.querySelector('#detailModal > div > div:last-child');\n" +
                "    if(!container) return;\n" +
                "    var html = '<div style=\"display:flex;flex-direction:column;gap:14px;\">';\n" +
                "    var fields = [\n" +
                "        {key:'用户名',icon:'👤'},\n" +
                "        {key:'登录时间',icon:'🕐'},\n" +
                "        {key:'登录结果',icon:'📊'},\n" +
                "        {key:'结果码',icon:'🔢'},\n" +
                "        {key:'结果消息',icon:'💬'},\n" +
                "        {key:'登录耗时',icon:'⏱️'},\n" +
                "        {key:'真实IP',icon:'🌐'},\n" +
                "        {key:'IP地理位置',icon:'📍'},\n" +
                "        {key:'请求方式',icon:'📨'},\n" +
                "        {key:'请求路径',icon:'🔗'},\n" +
                "        {key:'来源域名',icon:'🏠'},\n" +
                "        {key:'2FA类型',icon:'🔐'},\n" +
                "        {key:'2FA目标',icon:'📧'},\n" +
                "        {key:'2FA结果',icon:'✅'},\n" +
                "        {key:'设备信息',icon:'📱'}\n" +
                "    ];\n" +
                "    for(var i=0;i<fields.length;i++){\n" +
                "        var field = fields[i];\n" +
                "        var value = detail[field.key];\n" +
                "        if(value !== undefined && value !== null && value !== ''){\n" +
                "            html += '<div style=\"display:flex;border-bottom:1px solid #e8ecf1;padding:10px 0;\">' +\n" +
                "                '<div style=\"width:100px;font-weight:500;color:#64748b;display:flex;align-items:center;gap:6px;\">' +\n" +
                "                '<span>' + field.icon + '</span> ' + field.key + ':</div>' +\n" +
                "                '<div style=\"flex:1;color:#1e293b;word-break:break-all;\">' + escapeHtml(String(value)) + '</div>' +\n" +
                "                '</div>';\n" +
                "        }\n" +
                "    }\n" +
                "    html += '</div>';\n" +
                "    container.innerHTML = html;\n" +
                "}\n" +
                "function renderDetailError(msg) {\n" +
                "    var container = document.querySelector('#detailModal > div > div:last-child');\n" +
                "    if(container){\n" +
                "        container.innerHTML = '<div style=\"text-align:center;padding:40px;color:#ff4d4f;\">❌ ' + escapeHtml(msg) + '</div>';\n" +
                "    }\n" +
                "}\n" +
                "function closeDetailModal() {\n" +
                "    var modal = document.getElementById('detailModal');\n" +
                "    if(modal) modal.remove();\n" +
                "}\n" +
                "// 页面加载完成后执行\n" +
                "document.addEventListener('DOMContentLoaded', function() {\n" +
                "    loadStats();\n" +
                "    loadLogs();\n" +
                "});\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }

    private String generateNumberFilesHtml() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "<title>Texcell ECCOM WEB 客户号码文件管理</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='20' fill='%23667eea'/%3E%3Ctext x='50' y='68' font-size='50' text-anchor='middle' fill='white' font-family='Arial'%3E📁%3C/text%3E%3C/svg%3E\">\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap\" rel=\"stylesheet\">\n" +
                "<style>\n" +
                ":root {\n" +
                "  --bg-primary: #f5f7fa;\n" +
                "  --bg-card: #ffffff;\n" +
                "  --text-primary: #1e293b;\n" +
                "  --text-secondary: #64748b;\n" +
                "  --text-muted: #94a3b8;\n" +
                "  --border-color: #e8ecf1;\n" +
                "  --shadow: 0 1px 3px rgba(0,0,0,0.06);\n" +
                "  --navbar-bg: #ffffff;\n" +
                "  --hover-bg: rgba(0,0,0,0.02);\n" +
                "  --primary-color: #1677ff;\n" +
                "  --primary-hover: #4096ff;\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:#f5f7fa;font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;min-height:100vh;}\n" +
                ".page-wrap{max-width:2000px;margin:0 auto;padding:20px 24px;}\n" +
                ".navbar{background:#ffffff;box-shadow:0 1px 3px rgba(0,0,0,0.06);border:1px solid #e8ecf1;border-radius:12px;margin-bottom:24px;}\n" +
                ".nav-container{max-width:2000px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;padding:0 32px;height:70px;}\n" +
                ".logo{display:flex;align-items:center;gap:12px;}\n" +
                ".logo-icon{width:40px;height:40px;background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);border-radius:12px;display:flex;align-items:center;justify-content:center;font-size:20px;}\n" +
                ".logo-text{font-size:18px;font-weight:700;color:#1e293b;letter-spacing:1px;}\n" +
                ".logo-sub{font-size:11px;color:#94a3b8;margin-left:8px;}\n" +
                ".menu{display:flex;gap:12px;}\n" +
                ".menu a{color:#64748b;text-decoration:none;padding:8px 20px;border-radius:40px;transition:all 0.3s;font-weight:500;font-size:14px;}\n" +
                ".menu a:hover{background:#f1f5f9;color:#1e293b;}\n" +
                ".menu a.active{background:#e8f0fe;color:#1677ff;box-shadow:none;}\n" +
                ".user-info{display:flex;align-items:center;gap:20px;}\n" +
                ".user-name{color:#475569;font-size:14px;}\n" +
                ".user-role{background:rgba(22,119,255,0.1);padding:4px 12px;border-radius:20px;font-size:12px;color:#1677ff;}\n" +
                ".logout-btn{background:#f1f5f9;border:1px solid #e8ecf1;color:#475569;padding:8px 20px;border-radius:40px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".search-card{background:#ffffff;border-radius:12px;padding:20px 24px;margin-bottom:24px;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);}\n" +
                ".search-row{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-end;margin-bottom:16px;}\n" +
                ".search-row:last-child{margin-bottom:0;}\n" +
                ".search-group{display:flex;flex-direction:column;gap:6px;}\n" +
                ".search-group label{font-size:12px;color:#64748b;font-weight:500;}\n" +
                ".search-group input,.search-group select{padding:8px 12px;border:1px solid #d1d5db;border-radius:8px;font-size:13px;min-width:160px;transition:all 0.3s;background:#ffffff;color:#1e293b;}\n" +
                ".search-group input:focus,.search-group select:focus{outline:none;border-color:#1677ff;box-shadow:0 0 0 3px rgba(22,119,255,0.1);}\n" +
                ".search-group input[type=\"datetime-local\"]{min-width:180px;}\n" +
                ".search-actions{flex-direction:row;align-items:center;gap:10px;margin-left:auto;}\n" +
                ".search-btn{background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);color:white;border:none;padding:8px 20px;border-radius:8px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".search-btn:hover{transform:translateY(-1px);box-shadow:0 4px 12px rgba(22,119,255,0.3);}\n" +
                ".reset-btn{background:#f1f5f9;border:1px solid #d1d5db;padding:8px 20px;border-radius:8px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;color:#64748b;}\n" +
                ".reset-btn:hover{background:#e8ecf1;}\n" +
                ".stats-card{background:#ffffff;border-radius:12px;padding:20px 24px;margin-bottom:24px;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);display:flex;gap:32px;flex-wrap:wrap;}\n" +
                ".stat-item{display:flex;align-items:center;gap:12px;}\n" +
                ".stat-icon{font-size:28px;}\n" +
                ".stat-info{display:flex;flex-direction:column;}\n" +
                ".stat-number{font-size:24px;font-weight:700;color:#1677ff;}\n" +
                ".stat-label{font-size:12px;color:#94a3b8;}\n" +
                ".log-card{background:#ffffff;border-radius:12px;overflow:auto;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);}\n" +
                ".log-table{width:100%;border-collapse:collapse;min-width:800px;}\n" +
                ".log-table th{background:#f8fafc;padding:14px 16px;text-align:left;font-size:13px;font-weight:600;color:#64748b;border-bottom:1px solid #e8ecf1;}\n" +
                ".log-table td{padding:14px 16px;font-size:13px;color:#1e293b;border-bottom:1px solid #e8ecf1;}\n" +
                ".log-table tr:hover td{background:#f8fafc;}\n" +
                ".download-btn{background:linear-gradient(135deg,#52c41a 0%,#389e0d 100%);color:white;border:none;padding:6px 16px;border-radius:6px;cursor:pointer;font-size:12px;font-weight:500;transition:all 0.3s;}\n" +
                ".download-btn:hover{transform:translateY(-1px);box-shadow:0 2px 8px rgba(82,196,26,0.4);}\n" +
                ".pagination{display:flex;justify-content:center;gap:12px;padding:24px;border-top:1px solid #e8ecf1;}\n" +
                ".page-btn{padding:8px 16px;border:1px solid #d1d5db;background:#ffffff;color:#1e293b;border-radius:8px;cursor:pointer;font-size:13px;transition:all 0.3s;}\n" +
                ".page-btn:hover:not(:disabled){border-color:#1677ff;color:#1677ff;}\n" +
                ".page-btn:disabled{background:#f1f5f9;color:#94a3b8;cursor:not-allowed;}\n" +
                ".page-info{font-size:13px;color:#94a3b8;align-self:center;}\n" +
                ".footer{text-align:center;padding:32px 0;color:#94a3b8;font-size:12px;border-top:1px solid #e8ecf1;margin-top:16px;}\n" +
                ".empty{text-align:center;padding:48px;color:#94a3b8;}\n" +
                ".size-badge{background:#e8f0fe;color:#1677ff;padding:2px 8px;border-radius:4px;font-size:11px;display:inline-block;}\n" +
                "@media(max-width:900px){.search-actions{margin-left:0;width:100%;justify-content:flex-start;}}\n" +
                "@media(max-width:768px){.search-group input,.search-group select,.search-group input[type=\"datetime-local\"]{min-width:100%;width:100%;}.navbar{padding:0 20px;}.menu a{padding:4px 8px;font-size:11px;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"page-wrap\">\n" +
                "<div class=\"navbar\">\n" +
                "<div class=\"nav-container\">\n" +
                "<div class=\"logo\">\n" +
                "<div class=\"logo-icon\">📊</div>\n" +
                "<div class=\"logo-text\">ECCOM WEB 监控管理系统</div>\n" +
                "<div class=\"logo-sub\">v3.0</div>\n" +
                "</div>\n" +
                "<div class=\"menu\">\n" +
                "<a href=\"/monitor/index\">首页</a>\n" +
                "<a href=\"/monitor/dashboard\">服务监控</a>\n" +
                "<a href=\"/monitor/login-audit\">登录审计</a>\n" +
                "<a href=\"/monitor/number-files\" class=\"active\">号码备份</a>\n" +
                "<a href=\"/monitor/customer-management\">API客户管理</a>\n" +
                "<a href=\"/monitor/user-management\">登录账户管理</a>\n" +
                "<a href=\"/monitor/ops-management\">运维管理</a>\n" +
                "<a href=\"/monitor/queue-management\">发送队列管理</a>\n" +
                "<a href=\"/monitor/queue-monitor\">队列DR监控</a>\n" +
                "</div>\n" +
                "<div class=\"user-info\">\n" +
                "<span class=\"user-name\" id=\"currentUsername\">加载中...</span>\n" +
                "<span class=\"user-role\" id=\"currentUserRole\">加载中...</span>\n" +
                "<button class=\"logout-btn\" onclick=\"logout()\">退出</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"search-card\">\n" +
                "<div class=\"search-row\">\n" +
                "<div class=\"search-group\"><label>👤 客户账号</label><input type=\"text\" id=\"searchUsername\" placeholder=\"模糊查询\"></div>\n" +
                "<div class=\"search-group\"><label>📅 开始时间</label><input type=\"datetime-local\" id=\"startTime\"></div>\n" +
                "<div class=\"search-group\"><label>📅 结束时间</label><input type=\"datetime-local\" id=\"endTime\"></div>\n" +
                "<div class=\"search-group search-actions\"><button class=\"search-btn\" onclick=\"searchFiles()\">🔍 查询</button><button class=\"reset-btn\" onclick=\"resetSearch()\">🔄 重置</button></div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"stats-card\" id=\"statsCard\">\n" +
                "<div class=\"stat-item\"><div class=\"stat-icon\">📁</div><div class=\"stat-info\"><span class=\"stat-number\" id=\"totalFiles\">0</span><span class=\"stat-label\">总文件数</span></div></div>\n" +
                "<div class=\"stat-item\"><div class=\"stat-icon\">👥</div><div class=\"stat-info\"><span class=\"stat-number\" id=\"totalCustomers\">0</span><span class=\"stat-label\">客户数</span></div></div>\n" +
                "</div>\n" +
                "<div class=\"log-card\">\n" +
                "<table class=\"log-table\">\n" +
                "<thead>\n" +
                "<tr><th>任务ID</th><th>客户账号</th><th>文件名</th><th>号码数量</th><th>文件大小</th><th>备份时间</th><th>操作</th></tr>\n" +
                "</thead>\n" +
                "<tbody id=\"fileBody\"><tr><td colspan=\"7\" class=\"empty\">加载中...</td></tr>\n" +
                "</tbody>\n" +
                "</table>\n" +
                "<div class=\"pagination\" id=\"pagination\"></div>\n" +
                "</div>\n" +
                "<div class=\"footer\">📁 客户号码文件备份 | 文件保留3天自动清理 | 仅限内部使用</div>\n" +
                "</div>\n" +
                "<script>\n" +
                "let page=1,size=20,totalPages=1;\n" +
                "// 获取当前登录用户信息\n" +
                "function getCurrentUserInfo() {\n" +
                "    fetch('/api/monitor/current-user?secret=dn17ORUwf36Znl')\n" +
                "        .then(function(res) { return res.json(); })\n" +
                "        .then(function(data) {\n" +
                "            if (data.code === 200) {\n" +
                "                document.getElementById('currentUsername').innerText = data.data.username;\n" +
                "                var roleSpan = document.getElementById('currentUserRole');\n" +
                "                var roleText = data.data.role === 'ADMIN' ? '👑 管理员' : '🔧 NOC';\n" +
                "                roleSpan.innerText = roleText;\n" +
                "            }\n" +
                "        }).catch(function(err) { console.error('获取用户信息失败:', err); });\n" +
                "}\n" +
                "getCurrentUserInfo();\n" +
                "function logout(){fetch('/monitor/logout').then(function(){location.href='/monitor/login';});}\n" +
                "function formatFileSize(bytes){\n" +
                "  if(bytes===0) return '0 B';\n" +
                "  var k=1024;\n" +
                "  var sizes=['B','KB','MB','GB'];\n" +
                "  var i=Math.floor(Math.log(bytes)/Math.log(k));\n" +
                "  return parseFloat((bytes/Math.pow(k,i)).toFixed(2))+' '+sizes[i];\n" +
                "}\n" +
                "function formatDateTime(dateStr){\n" +
                "  if(!dateStr) return '-';\n" +
                "  return dateStr.replace('T',' ').substring(0,19);\n" +
                "}\n" +
                "function loadStats(){\n" +
                "  fetch('/api/monitor/number-files/stats?secret=dn17ORUwf36Znl')\n" +
                "    .then(res=>res.json())\n" +
                "    .then(data=>{\n" +
                "      if(data.code===200&&data.data){\n" +
                "        document.getElementById('totalFiles').innerText=data.data.totalFiles||0;\n" +
                "        document.getElementById('totalCustomers').innerText=data.data.totalCustomers||0;\n" +
                "      }\n" +
                "    }).catch(err=>console.error('加载统计失败:',err));\n" +
                "}\n" +
                "function searchFiles(){page=1;loadFiles();}\n" +
                "function resetSearch(){\n" +
                "  var usernameInput=document.getElementById('searchUsername');\n" +
                "  if(usernameInput) usernameInput.value='';\n" +
                "  var startTimeInput=document.getElementById('startTime');\n" +
                "  if(startTimeInput) startTimeInput.value='';\n" +
                "  var endTimeInput=document.getElementById('endTime');\n" +
                "  if(endTimeInput) endTimeInput.value='';\n" +
                "  page=1;\n" +
                "  loadFiles();\n" +
                "}\n" +
                "function loadFiles(){\n" +
                "  var username=document.getElementById('searchUsername')?document.getElementById('searchUsername').value:'';\n" +
                "  var st=document.getElementById('startTime')?document.getElementById('startTime').value:'';\n" +
                "  var et=document.getElementById('endTime')?document.getElementById('endTime').value:'';\n" +
                "  var url='/api/monitor/number-files?secret=dn17ORUwf36Znl&page='+page+'&size='+size;\n" +
                "  if(username) url+='&username='+encodeURIComponent(username);\n" +
                "  if(st&&st.trim()!==''){\n" +
                "    var startTimeStr=st.replace('T',' ');\n" +
                "    if(startTimeStr.length===16) startTimeStr+=':00';\n" +
                "    url+='&startTime='+encodeURIComponent(startTimeStr);\n" +
                "  }\n" +
                "  if(et&&et.trim()!==''){\n" +
                "    var endTimeStr=et.replace('T',' ');\n" +
                "    if(endTimeStr.length===16) endTimeStr+=':00';\n" +
                "    url+='&endTime='+encodeURIComponent(endTimeStr);\n" +
                "  }\n" +
                "  fetch(url).then(res=>res.json()).then(data=>{\n" +
                "    if(data.code===200){\n" +
                "      renderTable(data.data||[]);\n" +
                "      totalPages=data.totalPages||1;\n" +
                "      renderPagination();\n" +
                "    }else{\n" +
                "      document.getElementById('fileBody').innerHTML='<tr><td colspan=\"7\" class=\"empty\">加载失败:'+(data.msg||'错误')+'</td></tr>';\n" +
                "    }\n" +
                "  }).catch(function(err){\n" +
                "    console.error('加载失败:',err);\n" +
                "    document.getElementById('fileBody').innerHTML='<tr><td colspan=\"7\" class=\"empty\">加载失败:网络错误</td></tr>';\n" +
                "  });\n" +
                "}\n" +
                "function renderTable(files){\n" +
                "  var tbody=document.getElementById('fileBody');\n" +
                "  if(!tbody) return;\n" +
                "  if(!files||files.length===0){\n" +
                "    tbody.innerHTML='<tr><td colspan=\"7\" class=\"empty\">暂无数据</td></tr>';\n" +
                "    return;\n" +
                "  }\n" +
                "  var html='';\n" +
                "  for(var i=0;i<files.length;i++){\n" +
                "    var f=files[i];\n" +
                "    html+='<tr>'+\n" +
                "      '<td style=\"font-family:monospace;font-size:12px;\">'+escapeHtml(f.taskId||'')+'</td>'+\n" +
                "      '<td><span class=\"size-badge\">'+escapeHtml(f.username||'')+'</span></td>'+\n" +
                "      '<td>'+escapeHtml(f.fileName||'')+'</td>'+\n" +
                "      '<td>'+formatNumber(f.numberCount||0)+'</td>'+\n" +
                "      '<td>'+formatFileSize(f.fileSize||0)+'</td>'+\n" +
                "      '<td>'+formatDateTime(f.createTime||'')+'</td>'+\n" +
                "      '<td><button class=\"download-btn\" onclick=\"downloadFile('+(f.id||0)+')\">📥 下载</button></td>'+\n" +
                "      '</tr>';\n" +
                "  }\n" +
                "  tbody.innerHTML=html;\n" +
                "}\n" +
                "function formatNumber(num){return num!==undefined?num.toLocaleString():'0';}\n" +
                "function renderPagination(){\n" +
                "  var paginationDiv=document.getElementById('pagination');\n" +
                "  if(!paginationDiv) return;\n" +
                "  if(totalPages<=1){paginationDiv.innerHTML='';return;}\n" +
                "  var html='<button class=\"page-btn\" onclick=\"goPage(1)\"'+(page===1?'disabled':'')+'>首页</button>'+\n" +
                "    '<button class=\"page-btn\" onclick=\"goPage('+(page-1)+')\"'+(page===1?'disabled':'')+'>上一页</button>'+\n" +
                "    '<span class=\"page-info\">第 '+page+' / '+totalPages+' 页</span>'+\n" +
                "    '<button class=\"page-btn\" onclick=\"goPage('+(page+1)+')\"'+(page===totalPages?'disabled':'')+'>下一页</button>'+\n" +
                "    '<button class=\"page-btn\" onclick=\"goPage('+totalPages+')\"'+(page===totalPages?'disabled':'')+'>尾页</button>';\n" +
                "  paginationDiv.innerHTML=html;\n" +
                "}\n" +
                "function goPage(p){page=p;loadFiles();}\n" +
                "function downloadFile(id){\n" +
                "  if(!id) return;\n" +
                "  window.location.href='/api/monitor/number-files/download?id='+id+'&secret=dn17ORUwf36Znl';\n" +
                "}\n" +
                "function escapeHtml(s){if(!s)return'';return String(s).replace(/[&<>]/g,function(m){if(m==='&')return'&amp;';if(m==='<')return'&lt;';if(m==='>')return'&gt;';return m;});}\n" +
                "document.addEventListener('DOMContentLoaded',function(){\n" +
                "  loadStats();\n" +
                "  loadFiles();\n" +
                "});\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }

    private String generateCustomerManagementHtml() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "<title>Texcell ECCOM WEB API客户管理</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='20' fill='%23667eea'/%3E%3Ctext x='50' y='68' font-size='50' text-anchor='middle' fill='white' font-family='Arial'%3E👥%3C/text%3E%3C/svg%3E\">\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap\" rel=\"stylesheet\">\n" +
                "<style>\n" +
                ":root {\n" +
                "  --bg-primary: #f5f7fa;\n" +
                "  --bg-card: #ffffff;\n" +
                "  --text-primary: #1e293b;\n" +
                "  --text-secondary: #64748b;\n" +
                "  --text-muted: #94a3b8;\n" +
                "  --border-color: #e8ecf1;\n" +
                "  --shadow: 0 1px 3px rgba(0,0,0,0.06);\n" +
                "  --navbar-bg: #ffffff;\n" +
                "  --search-card-bg: #ffffff;\n" +
                "  --table-header-bg: #f8fafc;\n" +
                "  --table-hover: #f8fafc;\n" +
                "  --input-bg: #ffffff;\n" +
                "  --input-border: #d1d5db;\n" +
                "  --btn-reset-bg: #f1f5f9;\n" +
                "  --btn-reset-hover: #e8ecf1;\n" +
                "  --status-enabled: #52c41a;\n" +
                "  --status-disabled: #ff4d4f;\n" +
                "  --primary-color: #1677ff;\n" +
                "  --primary-hover: #4096ff;\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:#f5f7fa;font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;min-height:100vh;}\n" +
                ".page-wrap{max-width:2000px;margin:0 auto;padding:20px 24px;}\n" +
                ".navbar{background:#ffffff;box-shadow:0 1px 3px rgba(0,0,0,0.06);border:1px solid #e8ecf1;border-radius:12px;margin-bottom:24px;}\n" +
                ".nav-container{max-width:2000px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;padding:0 32px;height:70px;}\n" +
                ".logo{display:flex;align-items:center;gap:12px;}\n" +
                ".logo-icon{width:40px;height:40px;background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);border-radius:12px;display:flex;align-items:center;justify-content:center;font-size:20px;}\n" +
                ".logo-text{font-size:18px;font-weight:700;color:#1e293b;letter-spacing:1px;}\n" +
                ".logo-sub{font-size:11px;color:#94a3b8;margin-left:8px;}\n" +
                ".menu{display:flex;gap:12px;}\n" +
                ".menu a{color:#64748b;text-decoration:none;padding:8px 20px;border-radius:40px;transition:all 0.3s;font-weight:500;font-size:14px;}\n" +
                ".menu a:hover{background:#f1f5f9;color:#1e293b;}\n" +
                ".menu a.active{background:#e8f0fe;color:#1677ff;box-shadow:none;}\n" +
                ".user-info{display:flex;align-items:center;gap:20px;}\n" +
                ".user-name{color:#475569;font-size:14px;}\n" +
                ".user-role{background:rgba(22,119,255,0.1);padding:4px 12px;border-radius:20px;font-size:12px;color:#1677ff;}\n" +
                ".logout-btn{background:#f1f5f9;border:1px solid #e8ecf1;color:#475569;padding:8px 20px;border-radius:40px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".stats-card{background:#ffffff;border-radius:12px;padding:20px 24px;margin-bottom:24px;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);display:flex;gap:32px;flex-wrap:wrap;}\n" +
                ".stat-item{display:flex;align-items:center;gap:12px;}\n" +
                ".stat-icon{font-size:28px;}\n" +
                ".stat-number{font-size:24px;font-weight:700;color:#1677ff;}\n" +
                ".stat-label{font-size:12px;color:#94a3b8;}\n" +
                ".search-card{background:#ffffff;border-radius:12px;padding:20px 24px;margin-bottom:24px;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);}\n" +
                ".search-row{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-end;}\n" +
                ".search-group{display:flex;flex-direction:column;gap:6px;}\n" +
                ".search-group label{font-size:12px;color:#64748b;font-weight:500;}\n" +
                ".search-group input,.search-group select{padding:8px 12px;border:1px solid #d1d5db;border-radius:8px;font-size:13px;min-width:160px;transition:all 0.3s;background:#ffffff;color:#1e293b;}\n" +
                ".search-group input:focus,.search-group select:focus{outline:none;border-color:#1677ff;box-shadow:0 0 0 3px rgba(22,119,255,0.1);}\n" +
                ".search-actions{display:flex;gap:10px;margin-left:auto;align-items:center;}\n" +
                ".search-btn{background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);color:white;border:none;padding:8px 20px;border-radius:8px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".search-btn:hover{transform:translateY(-1px);box-shadow:0 4px 12px rgba(22,119,255,0.3);}\n" +
                ".reset-btn{background:#f1f5f9;border:1px solid #d1d5db;padding:8px 20px;border-radius:8px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;color:#64748b;}\n" +
                ".reset-btn:hover{background:#e8ecf1;}\n" +
                ".add-btn{background:linear-gradient(135deg,#52c41a 0%,#389e0d 100%);color:white;border:none;padding:8px 20px;border-radius:8px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".add-btn:hover{transform:translateY(-1px);box-shadow:0 4px 12px rgba(82,196,26,0.3);}\n" +
                ".log-card{background:#ffffff;border-radius:12px;overflow:auto;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);}\n" +
                ".log-table{width:100%;border-collapse:collapse;min-width:800px;}\n" +
                ".log-table th{background:#f8fafc;padding:14px 16px;text-align:left;font-size:13px;font-weight:600;color:#64748b;border-bottom:1px solid #e8ecf1;}\n" +
                ".log-table td{padding:14px 16px;font-size:13px;color:#1e293b;border-bottom:1px solid #e8ecf1;}\n" +
                ".log-table tr:hover td{background:#f8fafc;}\n" +
                ".status-badge{display:inline-block;padding:2px 10px;border-radius:12px;font-size:11px;font-weight:500;}\n" +
                ".status-enabled{background:rgba(82,196,26,0.1);color:#52c41a;}\n" +
                ".status-disabled{background:rgba(255,77,79,0.1);color:#ff4d4f;}\n" +
                ".action-btn{padding:4px 12px;border-radius:6px;cursor:pointer;font-size:12px;transition:all 0.3s;border:none;}\n" +
                ".edit-btn{background:#1677ff;color:white;}\n" +
                ".edit-btn:hover{background:#4096ff;}\n" +
                ".delete-btn{background:#ff4d4f;color:white;margin-left:8px;}\n" +
                ".delete-btn:hover{background:#ff7875;}\n" +
                ".pagination{display:flex;justify-content:center;gap:12px;padding:24px;border-top:1px solid #e8ecf1;}\n" +
                ".page-btn{padding:8px 16px;border:1px solid #d1d5db;background:#ffffff;color:#1e293b;border-radius:8px;cursor:pointer;font-size:13px;transition:all 0.3s;}\n" +
                ".page-btn:hover:not(:disabled){border-color:#1677ff;color:#1677ff;}\n" +
                ".page-btn:disabled{background:#f1f5f9;color:#94a3b8;cursor:not-allowed;}\n" +
                ".page-info{font-size:13px;color:#94a3b8;align-self:center;}\n" +
                ".footer{text-align:center;padding:32px 0;color:#94a3b8;font-size:12px;border-top:1px solid #e8ecf1;margin-top:16px;}\n" +
                ".modal{position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.6);z-index:10000;display:flex;align-items:center;justify-content:center;backdrop-filter:blur(4px);}\n" +
                ".modal-content{background:#ffffff;border-radius:12px;width:500px;max-width:90%;padding:24px;box-shadow:0 20px 60px rgba(0,0,0,0.15);}\n" +
                ".modal-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:20px;padding-bottom:12px;border-bottom:1px solid #e8ecf1;}\n" +
                ".modal-header h3{color:#1e293b;margin:0;}\n" +
                ".modal-close{background:none;border:none;font-size:28px;cursor:pointer;color:#94a3b8;}\n" +
                ".modal-close:hover{color:#1e293b;}\n" +
                ".form-group{margin-bottom:16px;}\n" +
                ".form-group label{display:block;font-size:13px;font-weight:500;color:#64748b;margin-bottom:6px;}\n" +
                ".form-group input,.form-group select,.form-group textarea{width:100%;padding:10px 12px;border:1px solid #d1d5db;border-radius:8px;font-size:14px;background:#ffffff;color:#1e293b;}\n" +
                ".form-group input:focus,.form-group select:focus,.form-group textarea:focus{outline:none;border-color:#1677ff;box-shadow:0 0 0 3px rgba(22,119,255,0.1);}\n" +
                ".modal-buttons{display:flex;gap:12px;justify-content:flex-end;margin-top:20px;}\n" +
                ".confirm-btn{background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);color:white;border:none;padding:8px 24px;border-radius:8px;cursor:pointer;font-weight:500;}\n" +
                ".cancel-btn{background:#f1f5f9;border:1px solid #d1d5db;padding:8px 24px;border-radius:8px;cursor:pointer;color:#64748b;}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"page-wrap\">\n" +
                "<div class=\"navbar\">\n" +
                "<div class=\"nav-container\">\n" +
                "<div class=\"logo\">\n" +
                "<div class=\"logo-icon\">📊</div>\n" +
                "<div class=\"logo-text\">ECCOM WEB 监控管理系统</div>\n" +
                "<div class=\"logo-sub\">v3.0</div>\n" +
                "</div>\n" +
                "<div class=\"menu\">\n" +
                "<a href=\"/monitor/index\">首页</a>\n" +
                "<a href=\"/monitor/dashboard\">服务监控</a>\n" +
                "<a href=\"/monitor/login-audit\">登录审计</a>\n" +
                "<a href=\"/monitor/number-files\">号码备份</a>\n" +
                "<a href=\"/monitor/customer-management\" class=\"active\">API客户管理</a>\n" +
                "<a href=\"/monitor/user-management\">登录账户管理</a>\n" +
                "<a href=\"/monitor/ops-management\">运维管理</a>\n" +
                "<a href=\"/monitor/queue-management\">发送队列管理</a>\n" +
                "<a href=\"/monitor/queue-monitor\">队列DR监控</a>\n" +
                "</div>\n" +
                "<div class=\"user-info\">\n" +
                "<span class=\"user-name\" id=\"currentUsername\">加载中...</span>\n" +
                "<span class=\"user-role\" id=\"currentUserRole\">加载中...</span>\n" +
                "<button class=\"logout-btn\" onclick=\"logout()\">退出</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"stats-card\" id=\"statsCard\">\n" +
                "<div class=\"stat-item\"><div class=\"stat-icon\">👥</div><div><span class=\"stat-number\" id=\"totalCount\">0</span><div class=\"stat-label\">总客户数</div></div></div>\n" +
                "<div class=\"stat-item\"><div class=\"stat-icon\">✅</div><div><span class=\"stat-number\" id=\"activeCount\">0</span><div class=\"stat-label\">已启用</div></div></div>\n" +
                "<div class=\"stat-item\"><div class=\"stat-icon\">❌</div><div><span class=\"stat-number\" id=\"disabledCount\">0</span><div class=\"stat-label\">已禁用</div></div></div>\n" +
                "</div>\n" +
                "<div class=\"search-card\">\n" +
                "<div class=\"search-row\">\n" +
                "<div class=\"search-group\"><label>👤 客户账号</label><input type=\"text\" id=\"searchUsername\" placeholder=\"模糊查询\"></div>\n" +
                "<div class=\"search-group\"><label>📊 状态</label><select id=\"searchStatus\"><option value=\"\">全部</option><option value=\"1\">启用</option><option value=\"0\">禁用</option></select></div>\n" +
                "<div class=\"search-actions\">\n" +
                "<button class=\"search-btn\" onclick=\"searchCustomers()\">🔍 查询</button>\n" +
                "<button class=\"reset-btn\" onclick=\"resetSearch()\">🔄 重置</button>\n" +
                "<button class=\"add-btn\" onclick=\"showAddModal()\">➕ 新增客户</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"log-card\">\n" +
                "<table class=\"log-table\">\n" +
                "<thead>\n" +
                "<tr><th>ID</th><th>客户账号</th><th>密码</th><th>状态</th><th>备注</th><th>创建时间</th><th>操作</th></tr>\n" +
                "</thead>\n" +
                "<tbody id=\"customerBody\"><tr><td colspan=\"7\" class=\"empty\">加载中...</td></tr></tbody>\n" +
                "</table>\n" +
                "<div class=\"pagination\" id=\"pagination\"></div>\n" +
                "</div>\n" +
                "<div class=\"footer\">👥 API客户管理 | 新增/编辑客户后需重启应用生效</div>\n" +
                "</div>\n" +
                "<div id=\"modal\" class=\"modal\" style=\"display:none;\">\n" +
                "<div class=\"modal-content\">\n" +
                "<div class=\"modal-header\"><h3 id=\"modalTitle\">新增客户</h3><button class=\"modal-close\" onclick=\"closeModal()\">&times;</button></div>\n" +
                "<input type=\"hidden\" id=\"editId\">\n" +
                "<div class=\"form-group\"><label>客户账号 *</label><input type=\"text\" id=\"inputUsername\" placeholder=\"请输入客户账号\"></div>\n" +
                "<div class=\"form-group\"><label>密码 *</label><input type=\"password\" id=\"inputPassword\" placeholder=\"请输入密码\"></div>\n" +
                "<div class=\"form-group\"><label>状态</label><select id=\"inputStatus\"><option value=\"1\">启用</option><option value=\"0\">禁用</option></select></div>\n" +
                "<div class=\"form-group\"><label>备注</label><textarea id=\"inputRemark\" rows=\"3\" placeholder=\"请输入备注\"></textarea></div>\n" +
                "<div class=\"modal-buttons\"><button class=\"confirm-btn\" onclick=\"saveCustomer()\">保存</button><button class=\"cancel-btn\" onclick=\"closeModal()\">取消</button></div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<script>\n" +
                "let page=1,size=20,totalPages=1;\n" +
                "// 获取当前登录用户信息\n" +
                "function getCurrentUserInfo() {\n" +
                "    fetch('/api/monitor/current-user?secret=dn17ORUwf36Znl')\n" +
                "        .then(function(res) { return res.json(); })\n" +
                "        .then(function(data) {\n" +
                "            if (data.code === 200) {\n" +
                "                document.getElementById('currentUsername').innerText = data.data.username;\n" +
                "                var roleSpan = document.getElementById('currentUserRole');\n" +
                "                var roleText = data.data.role === 'ADMIN' ? '👑 管理员' : '🔧 NOC';\n" +
                "                roleSpan.innerText = roleText;\n" +
                "            }\n" +
                "        }).catch(function(err) { console.error('获取用户信息失败:', err); });\n" +
                "}\n" +
                "getCurrentUserInfo();\n" +
                "function logout(){fetch('/monitor/logout').then(function(){location.href='/monitor/login';});}\n" +
                "function loadStats(){fetch('/api/monitor/customers/statistics?secret=dn17ORUwf36Znl').then(res=>res.json()).then(data=>{if(data.code===200&&data.data){document.getElementById('totalCount').innerText=data.data.total||0;document.getElementById('activeCount').innerText=data.data.active||0;document.getElementById('disabledCount').innerText=data.data.disabled||0;}}).catch(err=>console.error('加载统计失败:',err));}\n" +
                "function searchCustomers(){page=1;loadCustomers();}\n" +
                "function resetSearch(){document.getElementById('searchUsername').value='';document.getElementById('searchStatus').value='';page=1;loadCustomers();}\n" +
                "function loadCustomers(){var username=document.getElementById('searchUsername').value;var status=document.getElementById('searchStatus').value;var url='/api/monitor/customers?secret=dn17ORUwf36Znl&page='+page+'&size='+size;if(username)url+='&username='+encodeURIComponent(username);if(status)url+='&status='+status;fetch(url).then(res=>res.json()).then(data=>{if(data.code===200){renderTable(data.data||[]);totalPages=data.totalPages||1;renderPagination();}else{document.getElementById('customerBody').innerHTML='<tr><td colspan=\"7\" class=\"empty\">加载失败:'+(data.msg||'错误')+'</td></tr>';}}).catch(()=>{document.getElementById('customerBody').innerHTML='<tr><td colspan=\"7\" class=\"empty\">加载失败:网络错误</td></tr>';});}\n" +
                "function renderTable(list){var tbody=document.getElementById('customerBody');if(!tbody)return;if(!list||list.length===0){tbody.innerHTML='<tr><td colspan=\"7\" class=\"empty\">暂无数据</td></tr>';return;}var html='';for(var i=0;i<list.length;i++){var c=list[i];var statusClass=c.status===1?'status-enabled':'status-disabled';var statusText=c.status===1?'启用':'禁用';html+='<tr>'+\n" +
                "'<td>'+c.id+'</td>'+\n" +
                "'<td>'+escapeHtml(c.username||'')+'</td>'+\n" +
                "'<td>******</td>'+\n" +
                "'<td><span class=\"status-badge '+statusClass+'\">'+statusText+'</span></td>'+\n" +
                "'<td>'+escapeHtml(c.remark||'')+'</td>'+\n" +
                "'<td>'+formatDateTime(c.createTime||'')+'</td>'+\n" +
                "'<td><button class=\"action-btn edit-btn\" onclick=\"editCustomer('+c.id+')\">编辑</button><button class=\"action-btn delete-btn\" onclick=\"deleteCustomer('+c.id+')\">删除</button></td>'+\n" +
                "'</tr>';}tbody.innerHTML=html;}\n" +
                "function formatDateTime(dateStr){if(!dateStr)return'-';return dateStr.replace('T',' ').substring(0,19);}\n" +
                "function renderPagination(){var div=document.getElementById('pagination');if(!div)return;if(totalPages<=1){div.innerHTML='';return;}var html='<button class=\"page-btn\" onclick=\"goPage(1)\"'+(page===1?'disabled':'')+'>首页</button><button class=\"page-btn\" onclick=\"goPage('+(page-1)+')\"'+(page===1?'disabled':'')+'>上一页</button><span class=\"page-info\">第 '+page+' / '+totalPages+' 页</span><button class=\"page-btn\" onclick=\"goPage('+(page+1)+')\"'+(page===totalPages?'disabled':'')+'>下一页</button><button class=\"page-btn\" onclick=\"goPage('+totalPages+')\"'+(page===totalPages?'disabled':'')+'>尾页</button>';div.innerHTML=html;}\n" +
                "function goPage(p){page=p;loadCustomers();}\n" +
                "function showAddModal(){document.getElementById('modalTitle').innerText='新增客户';document.getElementById('editId').value='';document.getElementById('inputUsername').value='';document.getElementById('inputPassword').value='';document.getElementById('inputStatus').value='1';document.getElementById('inputRemark').value='';document.getElementById('modal').style.display='flex';}\n" +
                "function editCustomer(id){fetch('/api/monitor/customers?secret=dn17ORUwf36Znl&page=1&size=9999').then(res=>res.json()).then(data=>{if(data.code===200){var customer=data.data.find(c=>c.id===id);if(customer){document.getElementById('modalTitle').innerText='编辑客户';document.getElementById('editId').value=customer.id;document.getElementById('inputUsername').value=customer.username;document.getElementById('inputPassword').value='';document.getElementById('inputStatus').value=customer.status;document.getElementById('inputRemark').value=customer.remark||'';document.getElementById('modal').style.display='flex';}}});}\n" +
                "function saveCustomer(){var id=document.getElementById('editId').value;var username=document.getElementById('inputUsername').value.trim();var password=document.getElementById('inputPassword').value;var status=document.getElementById('inputStatus').value;var remark=document.getElementById('inputRemark').value;if(!username){alert('请输入客户账号');return;}if(!id && !password){alert('请输入密码');return;}var data={username:username,status:parseInt(status),remark:remark};if(id)data.id=parseInt(id);if(password)data.password=password;var url=id?'/api/monitor/customers':'/api/monitor/customers';var method=id?'PUT':'POST';fetch(url+'?secret=dn17ORUwf36Znl',{method:method,headers:{'Content-Type':'application/json'},body:JSON.stringify(data)}).then(res=>res.json()).then(data=>{if(data.code===200){closeModal();loadCustomers();loadStats();}else{alert(data.msg||'操作失败');}}).catch(err=>{alert('操作失败:'+err.message);});}\n" +
                "function deleteCustomer(id){if(!confirm('确定要删除该客户吗？删除后无法恢复！'))return;fetch('/api/monitor/customers?id='+id+'&secret=dn17ORUwf36Znl',{method:'DELETE'}).then(res=>res.json()).then(data=>{if(data.code===200){loadCustomers();loadStats();}else{alert(data.msg||'删除失败');}}).catch(err=>{alert('删除失败:'+err.message);});}\n" +
                "function closeModal(){document.getElementById('modal').style.display='none';}\n" +
                "function escapeHtml(s){if(!s)return'';return String(s).replace(/[&<>]/g,function(m){if(m==='&')return'&amp;';if(m==='<')return'&lt;';if(m==='>')return'&gt;';return m;});}\n" +
                "loadStats();loadCustomers();\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }


    private String generateUserManagementHtml() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "<title>Texcell ECCOM WEB 登录账户管理</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='20' fill='%23667eea'/%3E%3Ctext x='50' y='68' font-size='50' text-anchor='middle' fill='white' font-family='Arial'%3E👤%3C/text%3E%3C/svg%3E\">\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap\" rel=\"stylesheet\">\n" +
                "<style>\n" +
                ":root {\n" +
                "  --bg-primary: #f5f7fa;\n" +
                "  --bg-card: #ffffff;\n" +
                "  --text-primary: #1e293b;\n" +
                "  --text-secondary: #64748b;\n" +
                "  --text-muted: #94a3b8;\n" +
                "  --border-color: #e8ecf1;\n" +
                "  --shadow: 0 1px 3px rgba(0,0,0,0.06);\n" +
                "  --navbar-bg: #ffffff;\n" +
                "  --search-card-bg: #ffffff;\n" +
                "  --table-header-bg: #f8fafc;\n" +
                "  --table-hover: #f8fafc;\n" +
                "  --input-bg: #ffffff;\n" +
                "  --input-border: #d1d5db;\n" +
                "  --btn-reset-bg: #f1f5f9;\n" +
                "  --btn-reset-hover: #e8ecf1;\n" +
                "  --status-enabled: #52c41a;\n" +
                "  --status-disabled: #ff4d4f;\n" +
                "  --primary-color: #1677ff;\n" +
                "  --primary-hover: #4096ff;\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:#f5f7fa;font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;min-height:100vh;}\n" +
                ".page-wrap{max-width:2000px;margin:0 auto;padding:20px 24px;}\n" +
                ".navbar{background:#ffffff;box-shadow:0 1px 3px rgba(0,0,0,0.06);border:1px solid #e8ecf1;border-radius:12px;margin-bottom:24px;}\n" +
                ".nav-container{max-width:2000px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;padding:0 32px;height:70px;}\n" +
                ".logo{display:flex;align-items:center;gap:12px;}\n" +
                ".logo-icon{width:40px;height:40px;background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);border-radius:12px;display:flex;align-items:center;justify-content:center;font-size:20px;}\n" +
                ".logo-text{font-size:18px;font-weight:700;color:#1e293b;letter-spacing:1px;}\n" +
                ".logo-sub{font-size:11px;color:#94a3b8;margin-left:8px;}\n" +
                ".menu{display:flex;gap:12px;}\n" +
                ".menu a{color:#64748b;text-decoration:none;padding:8px 20px;border-radius:40px;transition:all 0.3s;font-weight:500;font-size:14px;}\n" +
                ".menu a:hover{background:#f1f5f9;color:#1e293b;}\n" +
                ".menu a.active{background:#e8f0fe;color:#1677ff;box-shadow:none;}\n" +
                ".user-info{display:flex;align-items:center;gap:20px;}\n" +
                ".user-name{color:#475569;font-size:14px;}\n" +
                ".user-role{background:rgba(22,119,255,0.1);padding:4px 12px;border-radius:20px;font-size:12px;color:#1677ff;}\n" +
                ".logout-btn{background:#f1f5f9;border:1px solid #e8ecf1;color:#475569;padding:8px 20px;border-radius:40px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".search-card{background:#ffffff;border-radius:12px;padding:20px 24px;margin-bottom:24px;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);}\n" +
                ".search-row{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-end;}\n" +
                ".search-group{display:flex;flex-direction:column;gap:6px;}\n" +
                ".search-group label{font-size:12px;color:#64748b;font-weight:500;}\n" +
                ".search-group input,.search-group select{padding:8px 12px;border:1px solid #d1d5db;border-radius:8px;font-size:13px;min-width:160px;transition:all 0.3s;background:#ffffff;color:#1e293b;}\n" +
                ".search-group input:focus,.search-group select:focus{outline:none;border-color:#1677ff;box-shadow:0 0 0 3px rgba(22,119,255,0.1);}\n" +
                ".search-actions{display:flex;gap:10px;margin-left:auto;align-items:center;}\n" +
                ".search-btn{background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);color:white;border:none;padding:8px 20px;border-radius:8px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".search-btn:hover{transform:translateY(-1px);box-shadow:0 4px 12px rgba(22,119,255,0.3);}\n" +
                ".reset-btn{background:#f1f5f9;border:1px solid #d1d5db;padding:8px 20px;border-radius:8px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;color:#64748b;}\n" +
                ".reset-btn:hover{background:#e8ecf1;}\n" +
                ".add-btn{background:linear-gradient(135deg,#52c41a 0%,#389e0d 100%);color:white;border:none;padding:8px 20px;border-radius:8px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".add-btn:hover{transform:translateY(-1px);box-shadow:0 4px 12px rgba(82,196,26,0.3);}\n" +
                ".log-card{background:#ffffff;border-radius:12px;overflow:auto;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);}\n" +
                ".log-table{width:100%;border-collapse:collapse;min-width:900px;}\n" +
                ".log-table th{background:#f8fafc;padding:14px 16px;text-align:left;font-size:13px;font-weight:600;color:#64748b;border-bottom:1px solid #e8ecf1;}\n" +
                ".log-table td{padding:14px 16px;font-size:13px;color:#1e293b;border-bottom:1px solid #e8ecf1;}\n" +
                ".log-table tr:hover td{background:#f8fafc;}\n" +
                ".status-badge{display:inline-block;padding:2px 10px;border-radius:12px;font-size:11px;font-weight:500;}\n" +
                ".status-enabled{background:rgba(82,196,26,0.1);color:#52c41a;}\n" +
                ".status-disabled{background:rgba(255,77,79,0.1);color:#ff4d4f;}\n" +
                ".action-btn{padding:4px 12px;border-radius:6px;cursor:pointer;font-size:12px;transition:all 0.3s;border:none;}\n" +
                ".edit-btn{background:#1677ff;color:white;}\n" +
                ".edit-btn:hover{background:#4096ff;}\n" +
                ".delete-btn{background:#ff4d4f;color:white;margin-left:8px;}\n" +
                ".delete-btn:hover{background:#ff7875;}\n" +
                ".reset-pwd-btn{background:#faad14;color:white;margin-left:8px;}\n" +
                ".reset-pwd-btn:hover{background:#ffc53d;}\n" +
                ".pagination{display:flex;justify-content:center;gap:12px;padding:24px;border-top:1px solid #e8ecf1;}\n" +
                ".page-btn{padding:8px 16px;border:1px solid #d1d5db;background:#ffffff;color:#1e293b;border-radius:8px;cursor:pointer;font-size:13px;transition:all 0.3s;}\n" +
                ".page-btn:hover:not(:disabled){border-color:#1677ff;color:#1677ff;}\n" +
                ".page-btn:disabled{background:#f1f5f9;color:#94a3b8;cursor:not-allowed;}\n" +
                ".page-info{font-size:13px;color:#94a3b8;align-self:center;}\n" +
                ".footer{text-align:center;padding:32px 0;color:#94a3b8;font-size:12px;border-top:1px solid #e8ecf1;margin-top:16px;}\n" +
                ".modal{position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.6);z-index:10000;display:flex;align-items:center;justify-content:center;backdrop-filter:blur(4px);}\n" +
                ".modal-content{background:#ffffff;border-radius:12px;width:500px;max-width:90%;padding:24px;box-shadow:0 20px 60px rgba(0,0,0,0.15);}\n" +
                ".modal-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:20px;padding-bottom:12px;border-bottom:1px solid #e8ecf1;}\n" +
                ".modal-header h3{color:#1e293b;margin:0;}\n" +
                ".modal-close{background:none;border:none;font-size:28px;cursor:pointer;color:#94a3b8;}\n" +
                ".modal-close:hover{color:#1e293b;}\n" +
                ".form-group{margin-bottom:16px;}\n" +
                ".form-group label{display:block;font-size:13px;font-weight:500;color:#64748b;margin-bottom:6px;}\n" +
                ".form-group input,.form-group select,.form-group textarea{width:100%;padding:10px 12px;border:1px solid #d1d5db;border-radius:8px;font-size:14px;background:#ffffff;color:#1e293b;}\n" +
                ".form-group input:focus,.form-group select:focus,.form-group textarea:focus{outline:none;border-color:#1677ff;box-shadow:0 0 0 3px rgba(22,119,255,0.1);}\n" +
                ".modal-buttons{display:flex;gap:12px;justify-content:flex-end;margin-top:20px;}\n" +
                ".confirm-btn{background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);color:white;border:none;padding:8px 24px;border-radius:8px;cursor:pointer;font-weight:500;}\n" +
                ".cancel-btn{background:#f1f5f9;border:1px solid #d1d5db;padding:8px 24px;border-radius:8px;cursor:pointer;color:#64748b;}\n" +
                ".info-text{font-size:12px;color:#94a3b8;margin-top:4px;}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"page-wrap\">\n" +
                "<div class=\"navbar\">\n" +
                "<div class=\"nav-container\">\n" +
                "<div class=\"logo\">\n" +
                "<div class=\"logo-icon\">📊</div>\n" +
                "<div class=\"logo-text\">ECCOM WEB 监控管理系统</div>\n" +
                "<div class=\"logo-sub\">v3.0</div>\n" +
                "</div>\n" +
                "<div class=\"menu\">\n" +
                "<a href=\"/monitor/index\">首页</a>\n" +
                "<a href=\"/monitor/dashboard\">服务监控</a>\n" +
                "<a href=\"/monitor/login-audit\">登录审计</a>\n" +
                "<a href=\"/monitor/number-files\">号码备份</a>\n" +
                "<a href=\"/monitor/customer-management\">API客户管理</a>\n" +
                "<a href=\"/monitor/user-management\" class=\"active\">登录账户管理</a>\n" +
                "<a href=\"/monitor/ops-management\">运维管理</a>\n" +
                "<a href=\"/monitor/queue-management\">发送队列管理</a>\n" +
                "<a href=\"/monitor/queue-monitor\">队列DR监控</a>\n" +
                "</div>\n" +
                "<div class=\"user-info\">\n" +
                "<span class=\"user-name\" id=\"currentUsername\">加载中...</span>\n" +
                "<span class=\"user-role\" id=\"currentUserRole\">加载中...</span>\n" +
                "<button class=\"logout-btn\" onclick=\"logout()\">退出</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"search-card\">\n" +
                "<div class=\"search-row\">\n" +
                "<div class=\"search-group\"><label>👤 用户名</label><input type=\"text\" id=\"searchUsername\" placeholder=\"模糊查询\"></div>\n" +
                "<div class=\"search-group\"><label>📊 状态</label><select id=\"searchStatus\"><option value=\"\">全部</option><option value=\"1\">启用</option><option value=\"0\">禁用</option></select></div>\n" +
                "<div class=\"search-actions\" id=\"searchActions\">\n" +
                "<button class=\"search-btn\" onclick=\"searchUsers()\">🔍 查询</button>\n" +
                "<button class=\"reset-btn\" onclick=\"resetSearch()\">🔄 重置</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"log-card\">\n" +
                "<table class=\"log-table\">\n" +
                "<thead>\n" +
                "<th>ID</th><th>用户名</th><th>密码</th><th>角色</th><th>状态</th><th>允许IP</th><th>备注</th><th>创建时间</th><th>操作</th>\n" +
                "</thead>\n" +
                "<tbody id=\"userBody\"><td colspan=\"9\" class=\"empty\">加载中...</td></tr>\n" +
                "</tbody>\n" +
                "</table>\n" +
                "<div class=\"pagination\" id=\"pagination\"></div>\n" +
                "</div>\n" +
                "<div class=\"footer\">👤 监控系统登录账户管理 | 支持多IP配置，逗号分隔 | NOC角色只能修改自己的信息和重置密码</div>\n" +
                "</div>\n" +
                "<!-- 编辑用户弹窗 -->\n" +
                "<div id=\"modal\" class=\"modal\" style=\"display:none;\">\n" +
                "<div class=\"modal-content\">\n" +
                "<div class=\"modal-header\"><h3 id=\"modalTitle\">编辑用户</h3><button class=\"modal-close\" onclick=\"closeModal()\">&times;</button></div>\n" +
                "<input type=\"hidden\" id=\"editId\">\n" +
                "<input type=\"hidden\" id=\"originalRole\">\n" +
                "<div class=\"form-group\"><label>用户名 *</label><input type=\"text\" id=\"inputUsername\" placeholder=\"请输入用户名\"></div>\n" +
                "<div class=\"form-group\"><label>新密码</label><input type=\"password\" id=\"inputPassword\" placeholder=\"留空则不修改密码\"><div class=\"info-text\">如需修改密码，请输入新密码</div></div>\n" +
                "<div class=\"form-group\" id=\"roleGroup\"><label>角色</label><select id=\"inputRole\"><option value=\"ADMIN\">管理员</option><option value=\"NOC\">NOC</option></select></div>\n" +
                "<div class=\"form-group\"><label>状态</label><select id=\"inputStatus\"><option value=\"1\">启用</option><option value=\"0\">禁用</option></select></div>\n" +
                "<div class=\"form-group\"><label>允许IP</label><input type=\"text\" id=\"inputAllowedIps\" placeholder=\"多个IP用逗号分隔，如：192.168.1.*,10.0.0.1\"></div>\n" +
                "<div class=\"form-group\"><label>备注</label><textarea id=\"inputRemark\" rows=\"2\" placeholder=\"请输入备注\"></textarea></div>\n" +
                "<div class=\"modal-buttons\"><button class=\"confirm-btn\" onclick=\"saveUser()\">保存</button><button class=\"cancel-btn\" onclick=\"closeModal()\">取消</button></div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<!-- 重置密码弹窗 -->\n" +
                "<div id=\"resetPwdModal\" class=\"modal\" style=\"display:none;\">\n" +
                "<div class=\"modal-content\">\n" +
                "<div class=\"modal-header\"><h3>重置密码</h3><button class=\"modal-close\" onclick=\"closeResetPwdModal()\">&times;</button></div>\n" +
                "<input type=\"hidden\" id=\"resetUserId\">\n" +
                "<div class=\"form-group\"><label>用户名</label><input type=\"text\" id=\"resetUsername\" readonly disabled style=\"background:var(--btn-reset-bg);\"></div>\n" +
                "<div class=\"form-group\"><label>新密码</label><input type=\"password\" id=\"resetPassword\" placeholder=\"请输入新密码\"><div class=\"info-text\">密码长度至少6位，建议包含大小写字母和数字</div></div>\n" +
                "<div class=\"form-group\"><label>确认密码</label><input type=\"password\" id=\"resetConfirmPassword\" placeholder=\"请再次输入新密码\"></div>\n" +
                "<div class=\"modal-buttons\"><button class=\"confirm-btn\" onclick=\"confirmResetPassword()\">确认重置</button><button class=\"cancel-btn\" onclick=\"closeResetPwdModal()\">取消</button></div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<script>\n" +
                "var page=1,size=20,totalPages=1;\n" +
                "var currentUserRole = '';\n" +
                "var currentUsername = '';\n" +
                "\n" +
                "function logout(){fetch('/monitor/logout').then(function(){location.href='/monitor/login';});}\n" +
                "\n" +
                "// 获取当前登录用户信息\n" +
                "function getCurrentUserInfo(){\n" +
                "    fetch('/api/monitor/current-user?secret=dn17ORUwf36Znl')\n" +
                "        .then(res=>res.json())\n" +
                "        .then(data=>{\n" +
                "            if(data.code===200){\n" +
                "                currentUsername = data.data.username;\n" +
                "                currentUserRole = data.data.role;\n" +
                "                document.getElementById('currentUsername').innerText = currentUsername;\n" +
                "                document.getElementById('currentUserRole').innerText = currentUserRole === 'ADMIN' ? '👑 管理员' : '🔧 NOC';\n" +
                "                \n" +
                "                // 根据角色控制UI显示\n" +
                "                if(currentUserRole === 'NOC'){\n" +
                "                    var searchActions = document.getElementById('searchActions');\n" +
                "                    if(searchActions) searchActions.style.display = 'flex';\n" +
                "                } else {\n" +
                "                    var searchActions = document.getElementById('searchActions');\n" +
                "                    if(searchActions && !document.getElementById('addBtnExists')){\n" +
                "                        var addBtn = document.createElement('button');\n" +
                "                        addBtn.className = 'add-btn';\n" +
                "                        addBtn.innerHTML = '➕ 新增用户';\n" +
                "                        addBtn.onclick = function(){ showAddModal(); };\n" +
                "                        addBtn.id = 'addBtnExists';\n" +
                "                        searchActions.appendChild(addBtn);\n" +
                "                    }\n" +
                "                }\n" +
                "            }\n" +
                "        }).catch(err=>console.error('获取用户信息失败:',err));\n" +
                "}\n" +
                "\n" +
                "function searchUsers(){page=1;loadUsers();}\n" +
                "function resetSearch(){document.getElementById('searchUsername').value='';document.getElementById('searchStatus').value='';page=1;loadUsers();}\n" +
                "\n" +
                "function loadUsers(){\n" +
                "    var username=document.getElementById('searchUsername').value;\n" +
                "    var status=document.getElementById('searchStatus').value;\n" +
                "    var url='/api/monitor/users?secret=dn17ORUwf36Znl&page='+page+'&size='+size;\n" +
                "    if(username)url+='&username='+encodeURIComponent(username);\n" +
                "    if(status)url+='&status='+status;\n" +
                "    fetch(url).then(res=>res.json()).then(data=>{\n" +
                "        if(data.code===200){\n" +
                "            renderTable(data.data||[]);\n" +
                "            totalPages=data.totalPages||1;\n" +
                "            renderPagination();\n" +
                "        }else{\n" +
                "            document.getElementById('userBody').innerHTML='<tr><td colspan=\"9\" class=\"empty\">加载失败:'+(data.msg||'错误')+'</td></tr>';\n" +
                "        }\n" +
                "    }).catch(()=>{\n" +
                "        document.getElementById('userBody').innerHTML='<tr><td colspan=\"9\" class=\"empty\">加载失败:网络错误</td></tr>';\n" +
                "    });\n" +
                "}\n" +
                "\n" +
                "function renderTable(list){\n" +
                "    var tbody=document.getElementById('userBody');\n" +
                "    if(!tbody)return;\n" +
                "    if(!list||list.length===0){\n" +
                "        tbody.innerHTML='<tr><td colspan=\"9\" class=\"empty\">暂无数据</td></tr>';\n" +
                "        return;\n" +
                "    }\n" +
                "    var html='';\n" +
                "    for(var i=0;i<list.length;i++){\n" +
                "        var u=list[i];\n" +
                "        var statusClass=u.status===1?'status-enabled':'status-disabled';\n" +
                "        var statusText=u.status===1?'启用':'禁用';\n" +
                "        \n" +
                "        var isSelf = (u.username === currentUsername);\n" +
                "        var isAdmin = (currentUserRole === 'ADMIN');\n" +
                "        var canEdit = isAdmin || isSelf;\n" +
                "        var canDelete = isAdmin && (u.username !== 'Administrator');\n" +
                "        var canResetPwd = isAdmin || isSelf;\n" +
                "        \n" +
                "        var actionsHtml = '';\n" +
                "        if(canEdit){\n" +
                "            actionsHtml += '<button class=\"action-btn edit-btn\" onclick=\"editUser('+u.id+')\">编辑</button>';\n" +
                "        }\n" +
                "        if(canResetPwd){\n" +
                "            actionsHtml += '<button class=\"action-btn reset-pwd-btn\" onclick=\"showResetPwdModal('+u.id+',\\''+escapeHtml(u.username)+'\\')\">重置密码</button>';\n" +
                "        }\n" +
                "        if(canDelete){\n" +
                "            actionsHtml += '<button class=\"action-btn delete-btn\" onclick=\"deleteUser('+u.id+')\">删除</button>';\n" +
                "        }\n" +
                "        if(actionsHtml === '') actionsHtml = '-';\n" +
                "        \n" +
                "        html+='<tr>'+\n" +
                "            '<td>'+u.id+'</td>'+\n" +
                "            '<td>'+escapeHtml(u.username||'')+'</td>'+\n" +
                "            '<td>******</td>'+\n" +
                "            '<td><span class=\"status-badge\">'+escapeHtml(u.role||'ADMIN')+'</span></td>'+\n" +
                "            '<td><span class=\"status-badge '+statusClass+'\">'+statusText+'</span></td>'+\n" +
                "            '<td>'+escapeHtml(u.allowedIps||'-')+'</td>'+\n" +
                "            '<td>'+escapeHtml(u.remark||'')+'</td>'+\n" +
                "            '<td>'+formatDateTime(u.createTime||'')+'</td>'+\n" +
                "            '<td>'+actionsHtml+'</td>'+\n" +
                "            '</tr>';\n" +
                "    }\n" +
                "    tbody.innerHTML=html;\n" +
                "}\n" +
                "\n" +
                "function formatDateTime(dateStr){if(!dateStr)return'-';return dateStr.replace('T',' ').substring(0,19);}\n" +
                "\n" +
                "function renderPagination(){var div=document.getElementById('pagination');if(!div)return;if(totalPages<=1){div.innerHTML='';return;}var html='<button class=\"page-btn\" onclick=\"goPage(1)\"'+(page===1?'disabled':'')+'>首页</button><button class=\"page-btn\" onclick=\"goPage('+(page-1)+')\"'+(page===1?'disabled':'')+'>上一页</button><span class=\"page-info\">第 '+page+' / '+totalPages+' 页</span><button class=\"page-btn\" onclick=\"goPage('+(page+1)+')\"'+(page===totalPages?'disabled':'')+'>下一页</button><button class=\"page-btn\" onclick=\"goPage('+totalPages+')\"'+(page===totalPages?'disabled':'')+'>尾页</button>';div.innerHTML=html;}\n" +
                "function goPage(p){page=p;loadUsers();}\n" +
                "\n" +
                "function showAddModal(){\n" +
                "    if(currentUserRole !== 'ADMIN'){\n" +
                "        alert('权限不足，只有管理员可以新增用户');\n" +
                "        return;\n" +
                "    }\n" +
                "    document.getElementById('modalTitle').innerText='新增用户';\n" +
                "    document.getElementById('editId').value='';\n" +
                "    document.getElementById('inputUsername').value='';\n" +
                "    document.getElementById('inputPassword').value='';\n" +
                "    document.getElementById('inputRole').value='NOC';\n" +
                "    document.getElementById('inputStatus').value='1';\n" +
                "    document.getElementById('inputAllowedIps').value='';\n" +
                "    document.getElementById('inputRemark').value='';\n" +
                "    document.getElementById('roleGroup').style.display = 'block';\n" +
                "    document.getElementById('modal').style.display='flex';\n" +
                "}\n" +
                "\n" +
                "function editUser(id){\n" +
                "    fetch('/api/monitor/users?secret=dn17ORUwf36Znl&page=1&size=9999')\n" +
                "        .then(res=>res.json())\n" +
                "        .then(data=>{\n" +
                "            if(data.code===200){\n" +
                "                var user=data.data.find(u=>u.id===id);\n" +
                "                if(user){\n" +
                "                    if(currentUserRole === 'NOC' && user.username !== currentUsername){\n" +
                "                        alert('权限不足，NOC用户只能编辑自己的账户');\n" +
                "                        return;\n" +
                "                    }\n" +
                "                    \n" +
                "                    document.getElementById('modalTitle').innerText='编辑用户';\n" +
                "                    document.getElementById('editId').value=user.id;\n" +
                "                    document.getElementById('inputUsername').value=user.username;\n" +
                "                    document.getElementById('inputPassword').value='';\n" +
                "                    document.getElementById('inputStatus').value=user.status;\n" +
                "                    document.getElementById('inputAllowedIps').value=user.allowedIps||'';\n" +
                "                    document.getElementById('inputRemark').value=user.remark||'';\n" +
                "                    document.getElementById('originalRole').value = user.role || 'NOC';\n" +
                "                    \n" +
                "                    if(currentUserRole === 'NOC'){\n" +
                "                        document.getElementById('roleGroup').style.display = 'none';\n" +
                "                    } else {\n" +
                "                        document.getElementById('roleGroup').style.display = 'block';\n" +
                "                        document.getElementById('inputRole').value=user.role||'NOC';\n" +
                "                    }\n" +
                "                    \n" +
                "                    document.getElementById('inputUsername').readOnly = true;\n" +
                "                    document.getElementById('inputUsername').style.background = 'var(--btn-reset-bg)';\n" +
                "                    \n" +
                "                    document.getElementById('modal').style.display='flex';\n" +
                "                }\n" +
                "            }\n" +
                "        });\n" +
                "}\n" +
                "\n" +
                "function saveUser(){\n" +
                "    var id=document.getElementById('editId').value;\n" +
                "    var username=document.getElementById('inputUsername').value.trim();\n" +
                "    var password=document.getElementById('inputPassword').value;\n" +
                "    var status=document.getElementById('inputStatus').value;\n" +
                "    var allowedIps=document.getElementById('inputAllowedIps').value;\n" +
                "    var remark=document.getElementById('inputRemark').value;\n" +
                "    \n" +
                "    if(!username){alert('请输入用户名');return;}\n" +
                "    \n" +
                "    var data={username:username,status:parseInt(status),allowedIps:allowedIps,remark:remark};\n" +
                "    \n" +
                "    if(currentUserRole === 'ADMIN'){\n" +
                "        var role=document.getElementById('inputRole').value;\n" +
                "        data.role=role;\n" +
                "    } else {\n" +
                "        var originalRole = document.getElementById('originalRole');\n" +
                "        if(originalRole && originalRole.value){\n" +
                "            data.role = originalRole.value;\n" +
                "        } else {\n" +
                "            data.role = 'NOC';\n" +
                "        }\n" +
                "    }\n" +
                "    \n" +
                "    if(id)data.id=parseInt(id);\n" +
                "    if(password)data.password=password;\n" +
                "    \n" +
                "    var url=id?'/api/monitor/users':'/api/monitor/users';\n" +
                "    var method=id?'PUT':'POST';\n" +
                "    \n" +
                "    fetch(url+'?secret=dn17ORUwf36Znl',{method:method,headers:{'Content-Type':'application/json'},body:JSON.stringify(data)})\n" +
                "        .then(res=>res.json())\n" +
                "        .then(data=>{\n" +
                "            if(data.code===200){\n" +
                "                closeModal();\n" +
                "                loadUsers();\n" +
                "            }else{\n" +
                "                alert(data.msg||'操作失败');\n" +
                "            }\n" +
                "        }).catch(err=>{alert('操作失败:'+err.message);});\n" +
                "}\n" +
                "\n" +
                "function deleteUser(id){\n" +
                "    if(currentUserRole !== 'ADMIN'){\n" +
                "        alert('权限不足，只有管理员可以删除用户');\n" +
                "        return;\n" +
                "    }\n" +
                "    if(!confirm('确定要删除该用户吗？删除后无法恢复！'))return;\n" +
                "    fetch('/api/monitor/users?id='+id+'&secret=dn17ORUwf36Znl',{method:'DELETE'})\n" +
                "        .then(res=>res.json())\n" +
                "        .then(data=>{\n" +
                "            if(data.code===200){\n" +
                "                loadUsers();\n" +
                "            }else{\n" +
                "                alert(data.msg||'删除失败');\n" +
                "            }\n" +
                "        }).catch(err=>{alert('删除失败:'+err.message);});\n" +
                "}\n" +
                "\n" +
                "function showResetPwdModal(userId, username){\n" +
                "    document.getElementById('resetUserId').value = userId;\n" +
                "    document.getElementById('resetUsername').value = username;\n" +
                "    document.getElementById('resetPassword').value = '';\n" +
                "    document.getElementById('resetConfirmPassword').value = '';\n" +
                "    document.getElementById('resetPwdModal').style.display = 'flex';\n" +
                "}\n" +
                "\n" +
                "function confirmResetPassword(){\n" +
                "    var userId = document.getElementById('resetUserId').value;\n" +
                "    var newPassword = document.getElementById('resetPassword').value;\n" +
                "    var confirmPassword = document.getElementById('resetConfirmPassword').value;\n" +
                "    \n" +
                "    if(!newPassword){\n" +
                "        alert('请输入新密码');\n" +
                "        return;\n" +
                "    }\n" +
                "    if(newPassword.length < 6){\n" +
                "        alert('密码长度至少6位');\n" +
                "        return;\n" +
                "    }\n" +
                "    if(newPassword !== confirmPassword){\n" +
                "        alert('两次输入的密码不一致');\n" +
                "        return;\n" +
                "    }\n" +
                "    \n" +
                "    fetch('/api/monitor/users/reset-password?secret=dn17ORUwf36Znl',{\n" +
                "        method:'POST',\n" +
                "        headers:{'Content-Type':'application/json'},\n" +
                "        body:JSON.stringify({id:parseInt(userId), password:newPassword})\n" +
                "    }).then(res=>res.json())\n" +
                "      .then(data=>{\n" +
                "          if(data.code===200){\n" +
                "              alert('密码重置成功');\n" +
                "              closeResetPwdModal();\n" +
                "          }else{\n" +
                "              alert(data.msg||'重置失败');\n" +
                "          }\n" +
                "      }).catch(err=>{alert('重置失败:'+err.message);});\n" +
                "}\n" +
                "\n" +
                "function closeModal(){\n" +
                "    document.getElementById('modal').style.display='none';\n" +
                "    var usernameInput = document.getElementById('inputUsername');\n" +
                "    usernameInput.readOnly = false;\n" +
                "    usernameInput.style.background = '';\n" +
                "}\n" +
                "\n" +
                "function closeResetPwdModal(){\n" +
                "    document.getElementById('resetPwdModal').style.display='none';\n" +
                "}\n" +
                "\n" +
                "function escapeHtml(s){if(!s)return'';return String(s).replace(/[&<>]/g,function(m){if(m==='&')return'&amp;';if(m==='<')return'&lt;';if(m==='>')return'&gt;';return m;});}\n" +
                "\n" +
                "getCurrentUserInfo();\n" +
                "loadUsers();\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }

    private String generateOpsManagementHtml() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "<title>Texcell ECCOM WEB 运维管理</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='20' fill='%23667eea'/%3E%3Ctext x='50' y='68' font-size='50' text-anchor='middle' fill='white' font-family='Arial'%3E🔧%3C/text%3E%3C/svg%3E\">\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap\" rel=\"stylesheet\">\n" +
                "<script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>\n" +
                "<style>\n" +
                ":root {\n" +
                "  --bg-primary: #f5f7fa;\n" +
                "  --bg-card: #ffffff;\n" +
                "  --text-primary: #1e293b;\n" +
                "  --text-secondary: #64748b;\n" +
                "  --text-muted: #94a3b8;\n" +
                "  --border-color: #e8ecf1;\n" +
                "  --shadow: 0 1px 3px rgba(0,0,0,0.06);\n" +
                "  --navbar-bg: #ffffff;\n" +
                "  --search-card-bg: #ffffff;\n" +
                "  --input-bg: #ffffff;\n" +
                "  --input-border: #d1d5db;\n" +
                "  --btn-reset-bg: #f1f5f9;\n" +
                "  --btn-reset-hover: #e8ecf1;\n" +
                "  --card-hover: #f8fafc;\n" +
                "  --log-header-bg: #f8fafc;\n" +
                "  --table-hover: #f8fafc;\n" +
                "  --primary-color: #1677ff;\n" +
                "  --primary-hover: #4096ff;\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:#f5f7fa;font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;min-height:100vh;}\n" +
                ".page-wrap{max-width:2000px;margin:0 auto;padding:20px 24px;}\n" +
                ".navbar{background:#ffffff;box-shadow:0 1px 3px rgba(0,0,0,0.06);border:1px solid #e8ecf1;border-radius:12px;margin-bottom:24px;}\n" +
                ".nav-container{max-width:2000px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;padding:0 32px;height:70px;}\n" +
                ".logo{display:flex;align-items:center;gap:12px;}\n" +
                ".logo-icon{width:40px;height:40px;background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);border-radius:12px;display:flex;align-items:center;justify-content:center;font-size:20px;}\n" +
                ".logo-text{font-size:18px;font-weight:700;color:#1e293b;letter-spacing:1px;}\n" +
                ".logo-sub{font-size:11px;color:#94a3b8;margin-left:8px;}\n" +
                ".menu{display:flex;gap:12px;}\n" +
                ".menu a{color:#64748b;text-decoration:none;padding:8px 20px;border-radius:40px;transition:all 0.3s;font-weight:500;font-size:14px;}\n" +
                ".menu a:hover{background:#f1f5f9;color:#1e293b;}\n" +
                ".menu a.active{background:#e8f0fe;color:#1677ff;box-shadow:none;}\n" +
                ".user-info{display:flex;align-items:center;gap:20px;}\n" +
                ".user-name{color:#475569;font-size:14px;}\n" +
                ".user-role{background:rgba(22,119,255,0.1);padding:4px 12px;border-radius:20px;font-size:12px;color:#1677ff;}\n" +
                ".logout-btn{background:#f1f5f9;border:1px solid #e8ecf1;color:#475569;padding:8px 20px;border-radius:40px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".title{font-size:20px;font-weight:700;color:#1e293b;margin-bottom:20px;display:flex;align-items:center;gap:10px;}\n" +
                ".title-icon{font-size:28px;}\n" +
                ".cards-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:24px;margin-bottom:32px;}\n" +
                ".card{background:#ffffff;border-radius:12px;padding:24px;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);transition:all 0.3s;}\n" +
                ".card:hover{transform:translateY(-2px);box-shadow:0 4px 12px rgba(0,0,0,0.06);border-color:#1677ff;}\n" +
                ".card-header{display:flex;align-items:center;gap:12px;margin-bottom:20px;padding-bottom:12px;border-bottom:1px solid #e8ecf1;}\n" +
                ".card-icon{font-size:32px;}\n" +
                ".card-title{font-size:18px;font-weight:700;color:#1e293b;}\n" +
                ".card-desc{font-size:13px;color:#94a3b8;margin-top:4px;}\n" +
                ".form-group{margin-bottom:16px;}\n" +
                ".form-group label{display:block;font-size:13px;font-weight:500;color:#64748b;margin-bottom:6px;}\n" +
                ".form-group input{width:100%;padding:10px 12px;border:1px solid #d1d5db;border-radius:8px;font-size:14px;background:#ffffff;color:#1e293b;}\n" +
                ".form-group input:focus{outline:none;border-color:#1677ff;box-shadow:0 0 0 3px rgba(22,119,255,0.1);}\n" +
                ".card-btn{background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);color:white;border:none;padding:10px 20px;border-radius:8px;cursor:pointer;font-weight:600;font-size:14px;transition:all 0.3s;width:100%;margin-top:8px;}\n" +
                ".card-btn:hover{transform:translateY(-2px);box-shadow:0 4px 12px rgba(22,119,255,0.3);}\n" +
                ".card-btn.danger{background:linear-gradient(135deg,#ff4d4f 0%,#ff7875 100%);}\n" +
                ".card-btn.warning{background:linear-gradient(135deg,#faad14 0%,#ffc53d 100%);}\n" +
                ".card-btn.danger:hover{box-shadow:0 4px 12px rgba(255,77,79,0.3);}\n" +
                ".result-area{margin-top:16px;padding:12px;background:#f8fafc;border-radius:8px;font-size:13px;color:#64748b;display:none;}\n" +
                ".result-area.show{display:block;}\n" +
                ".result-success{color:#52c41a;}\n" +
                ".result-error{color:#ff4d4f;}\n" +
                ".loading{opacity:0.6;pointer-events:none;}\n" +
                ".stats-section{margin-bottom:32px;}\n" +
                ".stats-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:16px;margin-bottom:20px;}\n" +
                ".stat-card{background:#ffffff;border-radius:12px;padding:20px;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);}\n" +
                ".stat-number{font-size:28px;font-weight:700;color:#1677ff;}\n" +
                ".stat-label{font-size:13px;color:#94a3b8;margin-top:4px;}\n" +
                ".stat-trend{font-size:12px;margin-top:8px;padding-top:8px;border-top:1px solid #e8ecf1;color:#64748b;}\n" +
                ".trend-up{color:#52c41a;}\n" +
                ".trend-down{color:#ff4d4f;}\n" +
                ".chart-row{display:grid;grid-template-columns:1fr 1fr;gap:20px;margin-bottom:24px;}\n" +
                ".chart-card{background:#ffffff;border-radius:12px;padding:20px;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);}\n" +
                ".chart-card h3{font-size:15px;font-weight:600;color:#1e293b;margin-bottom:12px;display:flex;align-items:center;gap:8px;}\n" +
                ".chart-container{height:300px;position:relative;}\n" +
                ".log-section{margin-top:32px;}\n" +
                ".section-title{font-size:17px;font-weight:600;color:#1e293b;margin-bottom:16px;display:flex;align-items:center;gap:8px;}\n" +
                ".section-title::before{content:'';width:4px;height:20px;background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);border-radius:4px;}\n" +
                ".search-card{background:#ffffff;border-radius:12px;padding:16px 20px;margin-bottom:16px;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);}\n" +
                ".search-row{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-end;}\n" +
                ".search-group{display:flex;flex-direction:column;gap:6px;}\n" +
                ".search-group label{font-size:12px;color:#64748b;font-weight:500;}\n" +
                ".search-group input,.search-group select{padding:8px 12px;border:1px solid #d1d5db;border-radius:8px;font-size:13px;min-width:140px;transition:all 0.3s;background:#ffffff;color:#1e293b;}\n" +
                ".search-actions{display:flex;gap:10px;margin-left:auto;align-items:center;}\n" +
                ".search-btn{background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);color:white;border:none;padding:8px 20px;border-radius:8px;cursor:pointer;font-weight:500;font-size:13px;}\n" +
                ".reset-btn{background:#f1f5f9;border:1px solid #d1d5db;padding:8px 20px;border-radius:8px;cursor:pointer;font-weight:500;font-size:13px;color:#64748b;}\n" +
                ".log-card{background:#ffffff;border-radius:12px;overflow:auto;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);}\n" +
                ".log-table{width:100%;border-collapse:collapse;min-width:800px;}\n" +
                ".log-table th{background:#f8fafc;padding:12px 16px;text-align:left;font-size:12px;font-weight:600;color:#64748b;border-bottom:1px solid #e8ecf1;}\n" +
                ".log-table td{padding:12px 16px;font-size:12px;color:#1e293b;border-bottom:1px solid #e8ecf1;}\n" +
                ".log-table tr:hover td{background:#f8fafc;}\n" +
                ".status-badge{display:inline-block;padding:2px 10px;border-radius:12px;font-size:11px;font-weight:500;}\n" +
                ".status-success{background:rgba(82,196,26,0.1);color:#52c41a;}\n" +
                ".status-fail{background:rgba(255,77,79,0.1);color:#ff4d4f;}\n" +
                ".pagination{display:flex;justify-content:center;gap:12px;padding:16px;border-top:1px solid #e8ecf1;flex-wrap:wrap;}\n" +
                ".page-btn{padding:6px 14px;border:1px solid #d1d5db;background:#ffffff;color:#1e293b;border-radius:8px;cursor:pointer;font-size:12px;transition:all 0.3s;}\n" +
                ".page-btn:hover:not(:disabled){border-color:#1677ff;color:#1677ff;}\n" +
                ".page-btn:disabled{opacity:0.5;cursor:not-allowed;background:#f1f5f9;}\n" +
                ".page-info{font-size:12px;color:#94a3b8;align-self:center;}\n" +
                ".footer{text-align:center;padding:32px 0;color:#94a3b8;font-size:12px;border-top:1px solid #e8ecf1;margin-top:16px;}\n" +
                ".empty{text-align:center;padding:48px;color:#94a3b8;}\n" +
                "@media(max-width:1024px){.stats-grid{grid-template-columns:repeat(2,1fr);}}\n" +
                "@media(max-width:768px){.stats-grid{grid-template-columns:1fr;}.chart-row{grid-template-columns:1fr;}.cards-grid{grid-template-columns:1fr;}.navbar{padding:12px 20px;}.menu a{padding:6px 12px;font-size:12px;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"page-wrap\">\n" +
                "<div class=\"navbar\">\n" +
                "<div class=\"nav-container\">\n" +
                "<div class=\"logo\">\n" +
                "<div class=\"logo-icon\">📊</div>\n" +
                "<div class=\"logo-text\">ECCOM WEB 监控管理系统</div>\n" +
                "<div class=\"logo-sub\">v3.0</div>\n" +
                "</div>\n" +
                "<div class=\"menu\">\n" +
                "<a href=\"/monitor/index\">首页</a>\n" +
                "<a href=\"/monitor/dashboard\">服务监控</a>\n" +
                "<a href=\"/monitor/login-audit\">登录审计</a>\n" +
                "<a href=\"/monitor/number-files\">号码备份</a>\n" +
                "<a href=\"/monitor/customer-management\">API客户管理</a>\n" +
                "<a href=\"/monitor/user-management\">登录账户管理</a>\n" +
                "<a href=\"/monitor/ops-management\" class=\"active\">运维管理</a>\n" +
                "<a href=\"/monitor/queue-management\">发送队列管理</a>\n" +
                "<a href=\"/monitor/queue-monitor\">队列DR监控</a>\n" +
                "</div>\n" +
                "<div class=\"user-info\">\n" +
                "<span class=\"user-name\" id=\"currentUsername\">加载中...</span>\n" +
                "<span class=\"user-role\" id=\"currentUserRole\">加载中...</span>\n" +
                "<button class=\"logout-btn\" onclick=\"logout()\">退出</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"title\"><span class=\"title-icon\">🔧</span> 运维管理工具</div>\n" +
                "<!-- 操作卡片区 -->\n" +
                "<div class=\"cards-grid\">\n" +
                "<div class=\"card\">\n" +
                "<div class=\"card-header\"><span class=\"card-icon\">🔐</span><div><div class=\"card-title\">重置2FA</div><div class=\"card-desc\">解绑用户的Google验证器，重置后用户需重新绑定</div></div></div>\n" +
                "<div class=\"form-group\"><label>客户账号</label><input type=\"text\" id=\"reset2faUsername\" placeholder=\"请输入客户账号\"></div>\n" +
                "<button class=\"card-btn\" onclick=\"reset2FA()\">🔄 重置2FA</button>\n" +
                "<div id=\"reset2faResult\" class=\"result-area\"></div>\n" +
                "</div>\n" +
                "<div class=\"card\">\n" +
                "<div class=\"card-header\"><span class=\"card-icon\">🔓</span><div><div class=\"card-title\">解除2FA</div><div class=\"card-desc\">解除用户的二次验证，用户将不再需要2FA验证</div></div></div>\n" +
                "<div class=\"form-group\"><label>客户账号</label><input type=\"text\" id=\"disable2faUsername\" placeholder=\"请输入客户账号\"></div>\n" +
                "<button class=\"card-btn warning\" onclick=\"disable2FA()\">🔓 解除2FA</button>\n" +
                "<div id=\"disable2faResult\" class=\"result-area\"></div>\n" +
                "</div>\n" +
                "<div class=\"card\">\n" +
                "<div class=\"card-header\"><span class=\"card-icon\">👤</span><div><div class=\"card-title\">解锁账户</div><div class=\"card-desc\">解除因多次登录失败被锁定的账户</div></div></div>\n" +
                "<div class=\"form-group\"><label>客户账号</label><input type=\"text\" id=\"unlockAccountUsername\" placeholder=\"请输入客户账号\"></div>\n" +
                "<button class=\"card-btn\" onclick=\"unlockAccount()\">🔓 解锁账户</button>\n" +
                "<div id=\"unlockAccountResult\" class=\"result-area\"></div>\n" +
                "</div>\n" +
                "<div class=\"card\">\n" +
                "<div class=\"card-header\"><span class=\"card-icon\">🌐</span><div><div class=\"card-title\">解锁IP</div><div class=\"card-desc\">解除因多次登录失败被锁定的IP</div></div></div>\n" +
                "<div class=\"form-group\"><label>IP地址或账号</label><input type=\"text\" id=\"unlockIpUsername\" placeholder=\"请输入IP地址\"></div>\n" +
                "<button class=\"card-btn danger\" onclick=\"unlockIp()\">🔓 解锁IP</button>\n" +
                "<div id=\"unlockIpResult\" class=\"result-area\"></div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<!-- 统计卡片区 -->\n" +
                "<div class=\"stats-section\">\n" +
                "<div class=\"stats-grid\" id=\"statsGrid\"></div>\n" +
                "</div>\n" +
                "<!-- 图表区 -->\n" +
                "<div class=\"chart-row\">\n" +
                "<div class=\"chart-card\"><h3>📊 近7天操作趋势</h3><div class=\"chart-container\"><canvas id=\"trendChart\"></canvas></div></div>\n" +
                "<div class=\"chart-card\"><h3>🥧 本月操作类型分布</h3><div class=\"chart-container\"><canvas id=\"typeChart\"></canvas></div></div>\n" +
                "</div>\n" +
                "<div class=\"chart-row\">\n" +
                "<div class=\"chart-card\"><h3>🏆 操作人排行 (本月Top 10)</h3><div class=\"chart-container\"><canvas id=\"operatorChart\"></canvas></div></div>\n" +
                "<div class=\"chart-card\"><h3>📈 成功率分析</h3><div class=\"chart-container\"><canvas id=\"successRateChart\"></canvas></div></div>\n" +
                "</div>\n" +
                "<!-- 日志列表区 -->\n" +
                "<div class=\"log-section\">\n" +
                "<div class=\"section-title\">📋 操作解除日志</div>\n" +
                "<div class=\"search-card\">\n" +
                "<div class=\"search-row\">\n" +
                "<div class=\"search-group\"><label>👤 操作人</label><input type=\"text\" id=\"searchOperator\" placeholder=\"模糊查询\"></div>\n" +
                "<div class=\"search-group\"><label>🔧 操作类型</label><select id=\"searchOperationType\"><option value=\"\">全部</option><option value=\"RESET_2FA\">重置2FA</option><option value=\"DISABLE_2FA\">解除2FA</option><option value=\"UNLOCK_ACCOUNT\">解锁账户</option><option value=\"UNLOCK_IP\">解锁IP</option></select></div>\n" +
                "<div class=\"search-group\"><label>📅 开始时间</label><input type=\"datetime-local\" id=\"startTime\"></div>\n" +
                "<div class=\"search-group\"><label>📅 结束时间</label><input type=\"datetime-local\" id=\"endTime\"></div>\n" +
                "<div class=\"search-actions\"><button class=\"search-btn\" onclick=\"searchLogs()\">🔍 查询</button><button class=\"reset-btn\" onclick=\"resetSearch()\">🔄 重置</button></div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"log-card\">\n" +
                "<table class=\"log-table\">\n" +
                "<thead>\n" +
                "<tr><th>时间</th><th>操作人</th><th>操作类型</th><th>操作目标</th><th>结果</th><th>结果消息</th><th>IP</th></tr>\n" +
                "</thead>\n" +
                "<tbody id=\"logBody\"><tr><td colspan=\"7\" class=\"empty\">加载中...</td></tr></tbody>\n" +
                "</table>\n" +
                "<div class=\"pagination\" id=\"pagination\"></div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"footer\">🔧 EC WEB运维管理 | 操作请谨慎，所有操作均有日志记录 | 仅限内部使用</div>\n" +
                "</div>\n" +
                "<script>\n" +
                "let page = 1, size = 20, totalPages = 1;\n" +
                "let trendChart = null, typeChart = null, operatorChart = null, successRateChart = null;\n" +
                "\n" +
                "function logout(){ fetch('/monitor/logout').then(function(){ location.href = '/monitor/login'; }); }\n" +
                "\n" +
                "// 获取当前登录用户信息\n" +
                "function getCurrentUserInfo() {\n" +
                "    fetch('/api/monitor/current-user?secret=dn17ORUwf36Znl')\n" +
                "        .then(function(res) { return res.json(); })\n" +
                "        .then(function(data) {\n" +
                "            if (data.code === 200) {\n" +
                "                document.getElementById('currentUsername').innerText = data.data.username;\n" +
                "                var roleSpan = document.getElementById('currentUserRole');\n" +
                "                var roleText = data.data.role === 'ADMIN' ? '👑 管理员' : '🔧 NOC';\n" +
                "                roleSpan.innerText = roleText;\n" +
                "            }\n" +
                "        }).catch(function(err) { console.error('获取用户信息失败:', err); });\n" +
                "}\n" +
                "getCurrentUserInfo();\n" +
                "\n" +
                "function showResult(elementId, success, message){\n" +
                "    var el = document.getElementById(elementId);\n" +
                "    if(!el) return;\n" +
                "    el.innerHTML = '<span class=\"' + (success ? 'result-success' : 'result-error') + '\">' + (success ? '✅ ' : '❌ ') + escapeHtml(message) + '</span>';\n" +
                "    el.classList.add('show');\n" +
                "    setTimeout(function(){ el.classList.remove('show'); }, 5000);\n" +
                "}\n" +
                "\n" +
                "function setLoading(btn, loading){\n" +
                "    if(loading){\n" +
                "        btn.classList.add('loading');\n" +
                "        btn.disabled = true;\n" +
                "    }else{\n" +
                "        btn.classList.remove('loading');\n" +
                "        btn.disabled = false;\n" +
                "    }\n" +
                "}\n" +
                "\n" +
                "function reset2FA(){\n" +
                "    var username = document.getElementById('reset2faUsername').value.trim();\n" +
                "    if(!username){ alert('请输入客户账号'); return; }\n" +
                "    var btn = event.target;\n" +
                "    setLoading(btn, true);\n" +
                "    fetch('/api/monitor/ops/reset-2fa?username=' + encodeURIComponent(username) + '&secret=dn17ORUwf36Znl', {method:'POST'})\n" +
                "        .then(res => res.json())\n" +
                "        .then(data => {\n" +
                "            setLoading(btn, false);\n" +
                "            if(data.code === 200){\n" +
                "                showResult('reset2faResult', true, '重置2FA成功');\n" +
                "                document.getElementById('reset2faUsername').value = '';\n" +
                "                loadStats(); loadLogs(); loadCharts();\n" +
                "            }else{\n" +
                "                showResult('reset2faResult', false, '操作失败: ' + (data.msg || '未知错误'));\n" +
                "            }\n" +
                "        }).catch(err => { setLoading(btn, false); showResult('reset2faResult', false, '网络错误: ' + err.message); });\n" +
                "}\n" +
                "\n" +
                "function disable2FA(){\n" +
                "    var username = document.getElementById('disable2faUsername').value.trim();\n" +
                "    if(!username){ alert('请输入客户账号'); return; }\n" +
                "    var btn = event.target;\n" +
                "    setLoading(btn, true);\n" +
                "    fetch('/api/monitor/ops/disable-2fa?username=' + encodeURIComponent(username) + '&secret=dn17ORUwf36Znl', {method:'POST'})\n" +
                "        .then(res => res.json())\n" +
                "        .then(data => {\n" +
                "            setLoading(btn, false);\n" +
                "            if(data.code === 200){\n" +
                "                showResult('disable2faResult', true, '解除2FA成功');\n" +
                "                document.getElementById('disable2faUsername').value = '';\n" +
                "                loadStats(); loadLogs(); loadCharts();\n" +
                "            }else{\n" +
                "                showResult('disable2faResult', false, '操作失败: ' + (data.msg || '未知错误'));\n" +
                "            }\n" +
                "        }).catch(err => { setLoading(btn, false); showResult('disable2faResult', false, '网络错误: ' + err.message); });\n" +
                "}\n" +
                "\n" +
                "function unlockAccount(){\n" +
                "    var username = document.getElementById('unlockAccountUsername').value.trim();\n" +
                "    if(!username){ alert('请输入客户账号'); return; }\n" +
                "    var btn = event.target;\n" +
                "    setLoading(btn, true);\n" +
                "    fetch('/api/monitor/ops/unlock-account?username=' + encodeURIComponent(username) + '&secret=dn17ORUwf36Znl', {method:'POST'})\n" +
                "        .then(res => res.json())\n" +
                "        .then(data => {\n" +
                "            setLoading(btn, false);\n" +
                "            if(data.code === 200){\n" +
                "                showResult('unlockAccountResult', true, '解锁账户成功');\n" +
                "                document.getElementById('unlockAccountUsername').value = '';\n" +
                "                loadStats(); loadLogs(); loadCharts();\n" +
                "            }else{\n" +
                "                showResult('unlockAccountResult', false, '操作失败: ' + (data.msg || '未知错误'));\n" +
                "            }\n" +
                "        }).catch(err => { setLoading(btn, false); showResult('unlockAccountResult', false, '网络错误: ' + err.message); });\n" +
                "}\n" +
                "\n" +
                "function unlockIp(){\n" +
                "    var username = document.getElementById('unlockIpUsername').value.trim();\n" +
                "    if(!username){ alert('请输入IP地址或账号'); return; }\n" +
                "    var btn = event.target;\n" +
                "    setLoading(btn, true);\n" +
                "    fetch('/api/monitor/ops/unlock-ip?username=' + encodeURIComponent(username) + '&secret=dn17ORUwf36Znl', {method:'POST'})\n" +
                "        .then(res => res.json())\n" +
                "        .then(data => {\n" +
                "            setLoading(btn, false);\n" +
                "            if(data.code === 200){\n" +
                "                showResult('unlockIpResult', true, '解锁IP成功');\n" +
                "                document.getElementById('unlockIpUsername').value = '';\n" +
                "                loadStats(); loadLogs(); loadCharts();\n" +
                "            }else{\n" +
                "                showResult('unlockIpResult', false, '操作失败: ' + (data.msg || '未知错误'));\n" +
                "            }\n" +
                "        }).catch(err => { setLoading(btn, false); showResult('unlockIpResult', false, '网络错误: ' + err.message); });\n" +
                "}\n" +
                "\n" +
                "function loadStats(){\n" +
                "    fetch('/api/monitor/ops-logs/stats?secret=dn17ORUwf36Znl')\n" +
                "        .then(res => res.json())\n" +
                "        .then(data => {\n" +
                "            if(data.code === 200 && data.data){\n" +
                "                var weekCompare = data.data.weekCompare || {};\n" +
                "                var thisWeek = weekCompare.this_week_count || 0;\n" +
                "                var lastWeek = weekCompare.last_week_count || 0;\n" +
                "                var trend = thisWeek - lastWeek;\n" +
                "                var trendPercent = lastWeek > 0 ? (trend / lastWeek * 100).toFixed(1) : (thisWeek > 0 ? 100 : 0);\n" +
                "                var trendClass = trend >= 0 ? 'trend-up' : 'trend-down';\n" +
                "                var trendIcon = trend >= 0 ? '↑' : '↓';\n" +
                "                \n" +
                "                var statsHtml = '<div class=\"stat-card\"><div class=\"stat-number\">' + thisWeek + '</div><div class=\"stat-label\">本周操作次数</div><div class=\"stat-trend ' + trendClass + '\">' + trendIcon + ' ' + Math.abs(trendPercent) + '% 较上周</div></div>' +\n" +
                "                    '<div class=\"stat-card\"><div class=\"stat-number\">' + lastWeek + '</div><div class=\"stat-label\">上周操作次数</div></div>';\n" +
                "                \n" +
                "                var monthStatsByType = data.data.monthStatsByType || [];\n" +
                "                var totalMonth = 0;\n" +
                "                for(var i = 0; i < monthStatsByType.length; i++){ totalMonth += monthStatsByType[i].count; }\n" +
                "                statsHtml += '<div class=\"stat-card\"><div class=\"stat-number\">' + totalMonth + '</div><div class=\"stat-label\">本月操作总数</div></div>';\n" +
                "                \n" +
                "                var monthStatsByOperator = data.data.monthStatsByOperator || [];\n" +
                "                var topOperator = monthStatsByOperator.length > 0 ? monthStatsByOperator[0].operator : '-';\n" +
                "                var topCount = monthStatsByOperator.length > 0 ? monthStatsByOperator[0].total_count : 0;\n" +
                "                statsHtml += '<div class=\"stat-card\"><div class=\"stat-number\">' + escapeHtml(topOperator) + '</div><div class=\"stat-label\">本月最活跃</div><div class=\"stat-trend\">操作 ' + topCount + ' 次</div></div>';\n" +
                "                \n" +
                "                document.getElementById('statsGrid').innerHTML = statsHtml;\n" +
                "            }\n" +
                "        }).catch(err => console.error('加载统计失败:', err));\n" +
                "}\n" +
                "\n" +
                "function loadCharts(){\n" +
                "    fetch('/api/monitor/ops-logs/stats?secret=dn17ORUwf36Znl')\n" +
                "        .then(res => res.json())\n" +
                "        .then(data => {\n" +
                "            if(data.code === 200 && data.data){\n" +
                "                var trendData = data.data.last7DaysTrend || [];\n" +
                "                var dates = [], counts = [];\n" +
                "                for(var i = 0; i < trendData.length; i++){\n" +
                "                    dates.push(trendData[i].date);\n" +
                "                    counts.push(trendData[i].count);\n" +
                "                }\n" +
                "                var ctx1 = document.getElementById('trendChart').getContext('2d');\n" +
                "                if(trendChart) trendChart.destroy();\n" +
                "                trendChart = new Chart(ctx1, {\n" +
                "                    type: 'line',\n" +
                "                    data: { labels: dates, datasets: [{ label: '操作次数', data: counts, borderColor: '#1677ff', backgroundColor: 'rgba(22,119,255,0.1)', fill: true, tension: 0.3 }] },\n" +
                "                    options: { responsive: true, maintainAspectRatio: true }\n" +
                "                });\n" +
                "                \n" +
                "                var typeData = data.data.monthStatsByType || [];\n" +
                "                var typeLabels = [], typeCounts = [];\n" +
                "                var typeNameMap = { 'RESET_2FA': '重置2FA', 'DISABLE_2FA': '解除2FA', 'UNLOCK_ACCOUNT': '解锁账户', 'UNLOCK_IP': '解锁IP' };\n" +
                "                for(var i = 0; i < typeData.length; i++){\n" +
                "                    typeLabels.push(typeNameMap[typeData[i].operation_type] || typeData[i].operation_name);\n" +
                "                    typeCounts.push(typeData[i].count);\n" +
                "                }\n" +
                "                var ctx2 = document.getElementById('typeChart').getContext('2d');\n" +
                "                if(typeChart) typeChart.destroy();\n" +
                "                typeChart = new Chart(ctx2, {\n" +
                "                    type: 'pie',\n" +
                "                    data: { labels: typeLabels, datasets: [{ data: typeCounts, backgroundColor: ['#1677ff', '#4096ff', '#52c41a', '#faad14'] }] },\n" +
                "                    options: { responsive: true, maintainAspectRatio: true, plugins: { legend: { position: 'right' } } }\n" +
                "                });\n" +
                "                \n" +
                "                var operatorData = data.data.monthStatsByOperator || [];\n" +
                "                var operatorLabels = [], operatorCounts = [];\n" +
                "                for(var i = 0; i < Math.min(operatorData.length, 10); i++){\n" +
                "                    operatorLabels.push(operatorData[i].operator);\n" +
                "                    operatorCounts.push(operatorData[i].total_count);\n" +
                "                }\n" +
                "                var ctx3 = document.getElementById('operatorChart').getContext('2d');\n" +
                "                if(operatorChart) operatorChart.destroy();\n" +
                "                operatorChart = new Chart(ctx3, {\n" +
                "                    type: 'bar',\n" +
                "                    data: { labels: operatorLabels, datasets: [{ label: '操作次数', data: operatorCounts, backgroundColor: '#1677ff', borderRadius: 8 }] },\n" +
                "                    options: { responsive: true, maintainAspectRatio: true, indexAxis: 'y' }\n" +
                "                });\n" +
                "                \n" +
                "                var successData = data.data.monthStatsByOperator || [];\n" +
                "                var successLabels = [], successRates = [];\n" +
                "                for(var i = 0; i < Math.min(successData.length, 10); i++){\n" +
                "                    var total = successData[i].total_count;\n" +
                "                    var successCount = successData[i].success_count;\n" +
                "                    var rate = total > 0 ? (successCount / total * 100).toFixed(1) : 0;\n" +
                "                    successLabels.push(successData[i].operator);\n" +
                "                    successRates.push(rate);\n" +
                "                }\n" +
                "                var ctx4 = document.getElementById('successRateChart').getContext('2d');\n" +
                "                if(successRateChart) successRateChart.destroy();\n" +
                "                successRateChart = new Chart(ctx4, {\n" +
                "                    type: 'bar',\n" +
                "                    data: { labels: successLabels, datasets: [{ label: '成功率 (%)', data: successRates, backgroundColor: '#52c41a', borderRadius: 8 }] },\n" +
                "                    options: { responsive: true, maintainAspectRatio: true, scales: { y: { max: 100, min: 0, ticks: { callback: function(val){ return val + '%'; } } } } }\n" +
                "                });\n" +
                "            }\n" +
                "        }).catch(err => console.error('加载图表失败:', err));\n" +
                "}\n" +
                "\n" +
                "function searchLogs(){ page = 1; loadLogs(); }\n" +
                "\n" +
                "function resetSearch(){\n" +
                "    document.getElementById('searchOperator').value = '';\n" +
                "    document.getElementById('searchOperationType').value = '';\n" +
                "    document.getElementById('startTime').value = '';\n" +
                "    document.getElementById('endTime').value = '';\n" +
                "    page = 1;\n" +
                "    loadLogs();\n" +
                "}\n" +
                "\n" +
                "function loadLogs(){\n" +
                "    var operator = document.getElementById('searchOperator').value;\n" +
                "    var operationType = document.getElementById('searchOperationType').value;\n" +
                "    var startTime = document.getElementById('startTime').value;\n" +
                "    var endTime = document.getElementById('endTime').value;\n" +
                "    var url = '/api/monitor/ops-logs?secret=dn17ORUwf36Znl&page=' + page + '&size=' + size;\n" +
                "    if(operator) url += '&operator=' + encodeURIComponent(operator);\n" +
                "    if(operationType) url += '&operationType=' + encodeURIComponent(operationType);\n" +
                "    if(startTime) url += '&startTime=' + encodeURIComponent(startTime.replace('T', ' ') + ':00');\n" +
                "    if(endTime) url += '&endTime=' + encodeURIComponent(endTime.replace('T', ' ') + ':00');\n" +
                "    fetch(url).then(res => res.json()).then(data => {\n" +
                "        if(data.code === 200){\n" +
                "            renderTable(data.data || []);\n" +
                "            totalPages = data.totalPages || 1;\n" +
                "            renderPagination();\n" +
                "        }else{\n" +
                "            document.getElementById('logBody').innerHTML = '<tr><td colspan=\"7\" class=\"empty\">加载失败: ' + (data.msg || '错误') + '</td></tr>';\n" +
                "        }\n" +
                "    }).catch(() => {\n" +
                "        document.getElementById('logBody').innerHTML = '<tr><td colspan=\"7\" class=\"empty\">加载失败: 网络错误</td></tr>';\n" +
                "    });\n" +
                "}\n" +
                "\n" +
                "function renderTable(list){\n" +
                "    var tbody = document.getElementById('logBody');\n" +
                "    if(!tbody) return;\n" +
                "    if(!list || list.length === 0){\n" +
                "        tbody.innerHTML = '<tr><td colspan=\"7\" class=\"empty\">暂无数据</td></tr>';\n" +
                "        return;\n" +
                "    }\n" +
                "    var operationNameMap = { 'RESET_2FA': '重置2FA', 'DISABLE_2FA': '解除2FA', 'UNLOCK_ACCOUNT': '解锁账户', 'UNLOCK_IP': '解锁IP' };\n" +
                "    var html = '';\n" +
                "    for(var i = 0; i < list.length; i++){\n" +
                "        var log = list[i];\n" +
                "        var opName = operationNameMap[log.operationType] || log.operationName;\n" +
                "        var statusClass = log.result === 1 ? 'status-success' : 'status-fail';\n" +
                "        var statusText = log.result === 1 ? '成功' : '失败';\n" +
                "        html += '<tr>' +\n" +
                "            '<td style=\"white-space:nowrap\">' + formatDateTime(log.createTime) + '</td>' +\n" +
                "            '<td>' + escapeHtml(log.operator) + '</td>' +\n" +
                "            '<td><span class=\"status-badge\">' + opName + '</span></td>' +\n" +
                "            '<td>' + escapeHtml(log.target) + '</td>' +\n" +
                "            '<td><span class=\"status-badge ' + statusClass + '\">' + statusText + '</span></td>' +\n" +
                "            '<td title=\"' + escapeHtml(log.resultMsg || '') + '\">' + (log.resultMsg ? (log.resultMsg.length > 30 ? escapeHtml(log.resultMsg.substring(0,30)) + '...' : escapeHtml(log.resultMsg)) : '-') + '</td>' +\n" +
                "            '<td>' + (log.requestIp || '-') + '</td>' +\n" +
                "            '</tr>';\n" +
                "    }\n" +
                "    tbody.innerHTML = html;\n" +
                "}\n" +
                "\n" +
                "function formatDateTime(dateStr){ if(!dateStr) return '-'; return dateStr.replace('T', ' ').substring(0,19); }\n" +
                "\n" +
                "function renderPagination(){\n" +
                "    var div = document.getElementById('pagination');\n" +
                "    if(!div) return;\n" +
                "    if(totalPages <= 1){ div.innerHTML = ''; return; }\n" +
                "    var html = '<button class=\"page-btn\" onclick=\"goPage(1)\"' + (page === 1 ? 'disabled' : '') + '>首页</button>' +\n" +
                "        '<button class=\"page-btn\" onclick=\"goPage(' + (page - 1) + ')\"' + (page === 1 ? 'disabled' : '') + '>上一页</button>' +\n" +
                "        '<span class=\"page-info\">第 ' + page + ' / ' + totalPages + ' 页</span>' +\n" +
                "        '<button class=\"page-btn\" onclick=\"goPage(' + (page + 1) + ')\"' + (page === totalPages ? 'disabled' : '') + '>下一页</button>' +\n" +
                "        '<button class=\"page-btn\" onclick=\"goPage(' + totalPages + ')\"' + (page === totalPages ? 'disabled' : '') + '>尾页</button>';\n" +
                "    div.innerHTML = html;\n" +
                "}\n" +
                "\n" +
                "function goPage(p){ page = p; loadLogs(); }\n" +
                "\n" +
                "function escapeHtml(s){ if(!s) return ''; return String(s).replace(/[&<>]/g, function(m){ if(m === '&') return '&amp;'; if(m === '<') return '&lt;'; if(m === '>') return '&gt;'; return m; }); }\n" +
                "\n" +
                "loadStats();\n" +
                "loadLogs();\n" +
                "loadCharts();\n" +
                "setInterval(function(){ loadStats(); loadCharts(); }, 30000);\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }

    private String generateQueueManagementHtml() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "<title>Texcell ECCOM WEB 发送队列管理</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='20' fill='%23667eea'/%3E%3Ctext x='50' y='68' font-size='50' text-anchor='middle' fill='white' font-family='Arial'%3E📨%3C/text%3E%3C/svg%3E\">\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap\" rel=\"stylesheet\">\n" +
                "<script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>\n" +
                "<style>\n" +
                ":root {\n" +
                "  --bg-primary: #f5f7fa;\n" +
                "  --bg-card: #ffffff;\n" +
                "  --text-primary: #1e293b;\n" +
                "  --text-secondary: #64748b;\n" +
                "  --text-muted: #94a3b8;\n" +
                "  --border-color: #e8ecf1;\n" +
                "  --shadow: 0 1px 3px rgba(0,0,0,0.06);\n" +
                "  --navbar-bg: #ffffff;\n" +
                "  --search-card-bg: #ffffff;\n" +
                "  --table-header-bg: #f8fafc;\n" +
                "  --table-hover: #f8fafc;\n" +
                "  --input-bg: #ffffff;\n" +
                "  --input-border: #d1d5db;\n" +
                "  --btn-reset-bg: #f1f5f9;\n" +
                "  --btn-reset-hover: #e8ecf1;\n" +
                "  --primary-color: #1677ff;\n" +
                "  --primary-hover: #4096ff;\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:#f5f7fa;font-family:'Inter',sans-serif;min-height:100vh;}\n" +
                ".page-wrap{max-width:2000px;margin:0 auto;padding:20px 24px;}\n" +
                ".navbar{background:#ffffff;box-shadow:0 1px 3px rgba(0,0,0,0.06);border:1px solid #e8ecf1;border-radius:12px;margin-bottom:24px;}\n" +
                ".nav-container{max-width:2000px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;padding:0 32px;height:70px;}\n" +
                ".logo{display:flex;align-items:center;gap:12px;}\n" +
                ".logo-icon{width:40px;height:40px;background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);border-radius:12px;display:flex;align-items:center;justify-content:center;font-size:20px;}\n" +
                ".logo-text{font-size:18px;font-weight:700;color:#1e293b;letter-spacing:1px;}\n" +
                ".logo-sub{font-size:11px;color:#94a3b8;margin-left:8px;}\n" +
                ".menu{display:flex;gap:12px;}\n" +
                ".menu a{color:#64748b;text-decoration:none;padding:8px 20px;border-radius:40px;transition:all 0.3s;font-weight:500;font-size:14px;}\n" +
                ".menu a:hover{background:#f1f5f9;color:#1e293b;}\n" +
                ".menu a.active{background:#e8f0fe;color:#1677ff;box-shadow:none;}\n" +
                ".user-info{display:flex;align-items:center;gap:20px;}\n" +
                ".user-name{color:#475569;font-size:14px;}\n" +
                ".user-role{background:rgba(22,119,255,0.1);padding:4px 12px;border-radius:20px;font-size:12px;color:#1677ff;}\n" +
                ".logout-btn{background:#f1f5f9;border:1px solid #e8ecf1;color:#475569;padding:8px 20px;border-radius:40px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".rank-card{background:#ffffff;border-radius:12px;padding:20px;margin-bottom:24px;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);}\n" +
                ".rank-title{font-size:16px;font-weight:600;color:#1e293b;margin-bottom:16px;display:flex;align-items:center;gap:8px;}\n" +
                ".rank-chart-container{height:300px;position:relative;}\n" +
                ".search-card{background:#ffffff;border-radius:12px;padding:20px 24px;margin-bottom:24px;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);}\n" +
                ".search-row{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-end;}\n" +
                ".search-group{display:flex;flex-direction:column;gap:6px;}\n" +
                ".search-group label{font-size:12px;color:#64748b;font-weight:500;}\n" +
                ".search-group input,.search-group select{padding:8px 12px;border:1px solid #d1d5db;border-radius:8px;font-size:13px;min-width:160px;background:#ffffff;color:#1e293b;}\n" +
                ".search-actions{display:flex;gap:10px;margin-left:auto;}\n" +
                ".search-btn{background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);color:white;border:none;padding:8px 20px;border-radius:8px;cursor:pointer;font-weight:500;}\n" +
                ".reset-btn{background:#f1f5f9;border:1px solid #d1d5db;padding:8px 20px;border-radius:8px;cursor:pointer;font-weight:500;color:#64748b;}\n" +
                ".log-card{background:#ffffff;border-radius:12px;overflow:auto;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);}\n" +
                ".log-table{width:100%;border-collapse:collapse;min-width:1200px;}\n" +
                ".log-table th{background:#f8fafc;padding:12px 16px;text-align:left;font-size:13px;font-weight:600;color:#64748b;border-bottom:1px solid #e8ecf1;}\n" +
                ".log-table td{padding:12px 16px;font-size:13px;color:#1e293b;border-bottom:1px solid #e8ecf1;}\n" +
                ".log-table tr:hover td{background:#f8fafc;}\n" +
                ".sms-content{max-width:350px;word-break:break-all;white-space:normal;line-height:1.4;}\n" +
                ".badge-subaccount{background:#e8f0fe;color:#1677ff;padding:2px 10px;border-radius:12px;font-size:11px;display:inline-block;}\n" +
                ".badge-delivered{color:#52c41a;font-weight:500;}\n" +
                ".badge-failed{color:#ff4d4f;font-weight:500;}\n" +
                ".view-btn{background:#1677ff;color:white;border:none;padding:6px 16px;border-radius:6px;cursor:pointer;font-size:12px;transition:all 0.3s;}\n" +
                ".view-btn:hover{background:#4096ff;}\n" +
                ".export-btn{background:#52c41a;color:white;border:none;padding:4px 12px;border-radius:6px;cursor:pointer;font-size:12px;transition:all 0.3s;margin-right:4px;}\n" +
                ".export-btn:hover{background:#389e0d;transform:translateY(-1px);}\n" +
                ".delete-btn{background:#ff4d4f;color:white;border:none;padding:4px 12px;border-radius:6px;cursor:pointer;font-size:12px;transition:all 0.3s;margin-right:4px;}\n" +
                ".delete-btn:hover{background:#ff7875;transform:translateY(-1px);}\n" +
                ".pagination{display:flex;justify-content:center;gap:12px;padding:24px;border-top:1px solid #e8ecf1;}\n" +
                ".page-btn{padding:8px 16px;border:1px solid #d1d5db;background:#ffffff;color:#1e293b;border-radius:8px;cursor:pointer;font-size:13px;}\n" +
                ".page-btn:hover:not(:disabled){border-color:#1677ff;color:#1677ff;}\n" +
                ".page-btn:disabled{opacity:0.5;cursor:not-allowed;background:#f1f5f9;}\n" +
                ".page-info{font-size:13px;color:#94a3b8;align-self:center;}\n" +
                ".footer{text-align:center;padding:32px 0;color:#94a3b8;font-size:12px;border-top:1px solid #e8ecf1;margin-top:16px;}\n" +
                ".empty{text-align:center;padding:48px;color:#94a3b8;}\n" +
                ".modal{position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.7);z-index:10000;display:flex;align-items:center;justify-content:center;}\n" +
                ".modal-content{background:#ffffff;border-radius:12px;width:1000px;max-width:90%;max-height:85vh;display:flex;flex-direction:column;box-shadow:0 20px 60px rgba(0,0,0,0.15);}\n" +
                ".modal-header{display:flex;justify-content:space-between;align-items:center;padding:16px 24px;border-bottom:1px solid #e8ecf1;}\n" +
                ".modal-header h3{color:#1e293b;margin:0;}\n" +
                ".modal-close{background:none;border:none;font-size:24px;cursor:pointer;color:#94a3b8;}\n" +
                ".modal-body{padding:20px 24px;overflow-y:auto;flex:1;}\n" +
                ".detail-summary{background:#f8fafc;border-radius:8px;padding:12px 16px;margin-bottom:20px;display:flex;gap:20px;flex-wrap:wrap;}\n" +
                ".detail-summary-item{display:flex;align-items:center;gap:8px;}\n" +
                ".detail-summary-label{font-size:12px;color:#94a3b8;}\n" +
                ".detail-summary-value{font-size:16px;font-weight:600;color:#1e293b;}\n" +
                ".detail-search{margin-bottom:16px;display:flex;gap:10px;}\n" +
                ".detail-search input{flex:1;padding:8px 12px;border:1px solid #d1d5db;border-radius:8px;background:#ffffff;color:#1e293b;}\n" +
                ".detail-search button{padding:8px 20px;background:#1677ff;color:white;border:none;border-radius:8px;cursor:pointer;}\n" +
                ".detail-table{width:100%;border-collapse:collapse;}\n" +
                ".detail-table th,.detail-table td{padding:10px 12px;text-align:left;border-bottom:1px solid #e8ecf1;}\n" +
                ".detail-table th{background:#f8fafc;font-weight:600;color:#64748b;}\n" +
                ".detail-status-success{color:#52c41a;font-weight:500;}\n" +
                ".detail-status-failed{color:#ff4d4f;font-weight:500;}\n" +
                ".detail-status-pending{color:#faad14;font-weight:500;}\n" +
                ".detail-pagination{display:flex;justify-content:center;gap:12px;margin-top:20px;padding-top:16px;border-top:1px solid #e8ecf1;}\n" +
                "@media(max-width:768px){.search-actions{margin-left:0;}.navbar{padding:0 20px;}.menu a{padding:6px 12px;font-size:12px;}.rank-chart-container{height:250px;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"page-wrap\">\n" +
                "<div class=\"navbar\">\n" +
                "<div class=\"nav-container\">\n" +
                "<div class=\"logo\">\n" +
                "<div class=\"logo-icon\">📊</div>\n" +
                "<div class=\"logo-text\">ECCOM WEB 监控管理系统</div>\n" +
                "<div class=\"logo-sub\">v3.0</div>\n" +
                "</div>\n" +
                "<div class=\"menu\">\n" +
                "<a href=\"/monitor/index\">首页</a>\n" +
                "<a href=\"/monitor/dashboard\">服务监控</a>\n" +
                "<a href=\"/monitor/login-audit\">登录审计</a>\n" +
                "<a href=\"/monitor/number-files\">号码备份</a>\n" +
                "<a href=\"/monitor/customer-management\">API客户管理</a>\n" +
                "<a href=\"/monitor/user-management\">登录账户管理</a>\n" +
                "<a href=\"/monitor/ops-management\">运维管理</a>\n" +
                "<a href=\"/monitor/queue-management\" class=\"active\">发送队列管理</a>\n" +
                "<a href=\"/monitor/queue-monitor\">队列DR监控</a>\n" +
                "</div>\n" +
                "<div class=\"user-info\">\n" +
                "<span class=\"user-name\" id=\"currentUsername\">加载中...</span>\n" +
                "<span class=\"user-role\" id=\"currentUserRole\">加载中...</span>\n" +
                "<button class=\"logout-btn\" onclick=\"logout()\">退出</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"rank-card\">\n" +
                "<div class=\"rank-title\">🏆 近30分钟客户提交量排行 (Top 10)</div>\n" +
                "<div class=\"rank-chart-container\">\n" +
                "<canvas id=\"rankingChart\"></canvas>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"search-card\">\n" +
                "<div class=\"search-row\">\n" +
                "<div class=\"search-group\"><label>👤 客户账号</label><input type=\"text\" id=\"searchSubAccount\" placeholder=\"模糊查询\"></div>\n" +
                "<div class=\"search-group\"><label>🆔 任务ID</label><input type=\"text\" id=\"searchTaskId\" placeholder=\"模糊查询\"></div>\n" +
                "<div class=\"search-actions\"><button class=\"search-btn\" onclick=\"searchTasks()\">🔍 查询</button><button class=\"reset-btn\" onclick=\"resetSearch()\">🔄 重置</button></div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"log-card\">\n" +
                "<table class=\"log-table\">\n" +
                "<thead><tr><th>任务ID</th><th>客户账号</th><th>任务名称</th><th>短信内容</th><th>创建时间</th><th>计划时间</th><th>总数</th><th>已提交</th><th>成功</th><th>失败</th><th>操作</th></tr></thead>\n" +
                "<tbody id=\"taskBody\"><tr><td colspan=\"11\" class=\"empty\">加载中...</td></tr></tbody>\n" +
                "</table>\n" +
                "<div class=\"pagination\" id=\"pagination\"></div>\n" +
                "</div>\n" +
                "<div class=\"footer\">📨 客户发送队列管理 | 仅显示最近3天数据 | 双击任务ID可查看详情</div>\n" +
                "</div>\n" +
                "<div id=\"detailModal\" class=\"modal\" style=\"display:none;\">\n" +
                "<div class=\"modal-content\">\n" +
                "<div class=\"modal-header\"><h3 id=\"modalTitle\">📋 任务详情</h3><button class=\"modal-close\" onclick=\"closeDetailModal()\">&times;</button></div>\n" +
                "<div class=\"modal-body\">\n" +
                "<div class=\"detail-summary\" id=\"detailSummary\"></div>\n" +
                "<div class=\"detail-search\"><input type=\"text\" id=\"detailCallee\" placeholder=\"按号码筛选\"><button onclick=\"loadTaskDetail()\">🔍 查询</button></div>\n" +
                "<div style=\"overflow-x:auto;\"><table class=\"detail-table\"><thead><tr><th>MsgId</th><th>发送方</th><th>接收号码</th><th>完成时间</th><th>状态</th><th>错误码</th><th>分片</th></tr></thead><tbody id=\"detailBody\"><tr><td colspan=\"7\" style=\"text-align:center;\">加载中...</td></tr></tbody></table></div>\n" +
                "<div class=\"detail-pagination\" id=\"detailPagination\"></div>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<script>\n" +
                "var page=1,size=10,totalPages=1;\n" +
                "var currentTaskId='';\n" +
                "var detailPage=1,detailSize=10,detailTotalPages=1;\n" +
                "var rankingChart=null;\n" +
                "function decodeSmsContent(str){if(!str)return'';try{var decoded=atob(str);var bytes=new Uint8Array(decoded.split('').map(function(c){return c.charCodeAt(0);}));return new TextDecoder('utf-8').decode(bytes);}catch(e){return str;}}\n" +
                "function logout(){fetch('/monitor/logout').then(function(){location.href='/monitor/login';});}\n" +
                "function getCurrentUserInfo() {\n" +
                "    fetch('/api/monitor/current-user?secret=dn17ORUwf36Znl')\n" +
                "        .then(function(res) { return res.json(); })\n" +
                "        .then(function(data) {\n" +
                "            if (data.code === 200) {\n" +
                "                document.getElementById('currentUsername').innerText = data.data.username;\n" +
                "                var roleSpan = document.getElementById('currentUserRole');\n" +
                "                var roleText = data.data.role === 'ADMIN' ? '👑 管理员' : '🔧 NOC';\n" +
                "                roleSpan.innerText = roleText;\n" +
                "            }\n" +
                "        }).catch(function(err) { console.error('获取用户信息失败:', err); });\n" +
                "}\n" +
                "getCurrentUserInfo();\n" +
                "function loadRanking(){fetch('/api/monitor/queue/customer-ranking?secret=dn17ORUwf36Znl').then(function(res){return res.json();}).then(function(data){var ctx=document.getElementById('rankingChart').getContext('2d');if(rankingChart)rankingChart.destroy();if(data.code===200&&data.data&&data.data.length){var list=data.data;var labels=list.map(function(i){return i.subAccount||i.username||'-';});var counts=list.map(function(i){return i.totalCount||0;});rankingChart=new Chart(ctx,{type:'bar',data:{labels:labels,datasets:[{label:'提交数量(条)',data:counts,backgroundColor:'#1677ff',borderRadius:8}]},options:{responsive:true,maintainAspectRatio:true}});}else{rankingChart=new Chart(ctx,{type:'bar',data:{labels:['暂无数据'],datasets:[{label:'提交数量',data:[0]}]},options:{responsive:true}});}}).catch(function(){var ctx=document.getElementById('rankingChart').getContext('2d');if(rankingChart)rankingChart.destroy();rankingChart=new Chart(ctx,{type:'bar',data:{labels:['加载失败'],datasets:[{label:'提交数量',data:[0]}]},options:{responsive:true}});});}\n" +
                "function formatNumber(num){if(!num)return'0';return num.toString().replace(/\\B(?=(\\d{3})+(?!\\d))/g,',');}\n" +
                "function searchTasks(){page=1;loadTasks();}\n" +
                "function resetSearch(){document.getElementById('searchSubAccount').value='';document.getElementById('searchTaskId').value='';page=1;loadTasks();}\n" +
                "function loadTasks(){var subAccount=document.getElementById('searchSubAccount').value;var taskId=document.getElementById('searchTaskId').value;var url='/api/monitor/queue/tasks?secret=dn17ORUwf36Znl&pageNum='+page+'&pageSize='+size;if(subAccount)url+='&subAccount='+encodeURIComponent(subAccount);if(taskId)url+='&taskId='+encodeURIComponent(taskId);fetch(url).then(function(res){return res.json();}).then(function(data){if(data.code===200){var records=data.data?.records||[];totalPages=Math.ceil((data.data?.total||0)/size);renderTable(records);renderPagination();}else{document.getElementById('taskBody').innerHTML='<tr><td colspan=\"11\" class=\"empty\">加载失败:'+(data.msg||'错误')+'</td></tr>';}}).catch(function(){document.getElementById('taskBody').innerHTML='<tr><td colspan=\"11\" class=\"empty\">加载失败:网络错误</td></tr>';});}\n" +
                "function escapeHtml(s){if(!s)return'';return String(s).replace(/[&<>]/g,function(m){return m==='&'?'&amp;':m==='<'?'&lt;':'&gt;';});}\n" +
                "function renderTable(list){var tbody=document.getElementById('taskBody');if(!list||!list.length){tbody.innerHTML='<tr><td colspan=\"11\" class=\"empty\">暂无数据</td></tr>';return;}var html='';for(var i=0;i<list.length;i++){var t=list[i];var smsContent=decodeSmsContent(t.shortMessage||'');var taskIdAttr=escapeHtml(String(t.taskId||''));var scheduleStatus = t.scheduleStatus !== undefined ? t.scheduleStatus : 0;html+='<tr class=\"task-row\" data-task-id=\"'+taskIdAttr+'\" style=\"cursor:pointer;\">';" +
                "html+='<td style=\"font-family:monospace;font-size:12px;\" title=\"'+escapeHtml(t.taskId||'')+'\">'+escapeHtml((t.taskId||'').substring(0,20))+'...</td>';" +
                "html+='<td><span class=\"badge-subaccount\">'+escapeHtml(t.subAccount||'')+'</span></td>';" +
                "html+='<td title=\"'+escapeHtml(t.taskName||'')+'\">'+escapeHtml((t.taskName||'').substring(0,30))+'</td>';" +
                "html+='<td class=\"sms-content\">'+escapeHtml(smsContent).replace(/\\n/g,'<br>')+'</td>';" +
                "html+='<td>'+escapeHtml(t.taskTime||'')+'</td>';" +
                "html+='<td>'+escapeHtml(t.scheduleSendTime||'')+'</td>';" +
                "html+='<td>'+formatNumber(t.totalCount||0)+'</td>';" +
                "html+='<td>'+formatNumber(t.submitSuccCount||0)+'</td>';" +
                "html+='<td class=\"badge-delivered\">'+formatNumber(t.drSuccCount||0)+'</td>';" +
                "html+='<td class=\"badge-failed\">'+formatNumber(t.drFailCount||0)+'</td>';" +
                "html+='<td>';" +
                "html+='<button class=\"view-btn\" onclick=\"showTaskDetail(\\''+taskIdAttr+'\\')\">\uD83D\uDCCB 详情</button> ';" +
                "html+='<button class=\"export-btn\" onclick=\"exportTask(\\''+taskIdAttr+'\\')\">\uD83D\uDCE5 导出</button> ';" +
                "if (scheduleStatus === 0) {" +
                "    html+='<button class=\"delete-btn\" onclick=\"deleteTask(\\''+taskIdAttr+'\\')\">\uD83D\uDDD1\uFE0F 删除</button>';" +
                "}" +
                "html+='</td></tr>';}tbody.innerHTML=html;}\n" +
                "function renderPagination(){var div=document.getElementById('pagination');if(!div||totalPages<=1){div.innerHTML='';return;}var html='<button class=\"page-btn\" onclick=\"goPage(1)\"'+(page===1?'disabled':'')+'>首页</button><button class=\"page-btn\" onclick=\"goPage('+(page-1)+')\"'+(page===1?'disabled':'')+'>上一页</button><span class=\"page-info\">第 '+page+' / '+totalPages+' 页</span><button class=\"page-btn\" onclick=\"goPage('+(page+1)+')\"'+(page===totalPages?'disabled':'')+'>下一页</button><button class=\"page-btn\" onclick=\"goPage('+totalPages+')\"'+(page===totalPages?'disabled':'')+'>尾页</button>';div.innerHTML=html;}\n" +
                "function goPage(p){page=p;loadTasks();}\n" +
                "function showTaskDetail(taskId){currentTaskId=taskId;detailPage=1;document.getElementById('modalTitle').innerHTML='📋 任务详情 - '+taskId;document.getElementById('detailCallee').value='';document.getElementById('detailModal').style.display='flex';loadTaskSummary();loadTaskDetail();}\n" +
                "function loadTaskSummary(){fetch('/api/monitor/queue/task/summary?taskId='+encodeURIComponent(currentTaskId)+'&secret=dn17ORUwf36Znl').then(function(res){return res.json();}).then(function(data){if(data.code===200&&data.data){var d=data.data;document.getElementById('detailSummary').innerHTML='<div class=\"detail-summary-item\"><span class=\"detail-summary-label\">任务名称:</span><span class=\"detail-summary-value\">'+escapeHtml(d.taskName||'-')+'</span></div><div class=\"detail-summary-item\"><span class=\"detail-summary-label\">客户账号:</span><span class=\"detail-summary-value\">'+escapeHtml(d.subAccount||'-')+'</span></div><div class=\"detail-summary-item\"><span class=\"detail-summary-label\">总数量:</span><span class=\"detail-summary-value\">'+formatNumber(d.totalCount||0)+'</span></div><div class=\"detail-summary-item\"><span class=\"detail-summary-label\">成功送达:</span><span class=\"detail-summary-value\">'+formatNumber(d.drSuccCount||0)+'</span></div><div class=\"detail-summary-item\"><span class=\"detail-summary-label\">失败:</span><span class=\"detail-summary-value\">'+formatNumber(d.drFailCount||0)+'</span></div>';}});}\n" +
                "function loadTaskDetail(){var callee=document.getElementById('detailCallee').value;var url='/api/monitor/queue/task/detail?secret=dn17ORUwf36Znl&taskId='+encodeURIComponent(currentTaskId)+'&pageNum='+detailPage+'&pageSize='+detailSize;if(callee)url+='&callee='+encodeURIComponent(callee);fetch(url).then(function(res){return res.json();}).then(function(data){if(data.code===200){var records=data.data?.records||[];var total=data.data?.total||0;detailTotalPages=Math.ceil(total/detailSize);renderDetailTable(records);renderDetailPagination();}else{document.getElementById('detailBody').innerHTML='<tr><td colspan=\"7\" style=\"text-align:center;\">加载失败:'+(data.msg||'错误')+'</td></tr>';}});}\n" +
                "function renderDetailTable(list){var tbody=document.getElementById('detailBody');if(!list||!list.length){tbody.innerHTML='<tr><td colspan=\"7\" style=\"text-align:center;\">暂无数据</td></tr>';return;}var html='';for(var i=0;i<list.length;i++){var d=list[i];var statusClass=(d.recvDRStatus==='DELIVRD')?'detail-status-success':((d.recvDRStatus==='UNDELIV'||d.recvDRStatus==='REJECTD')?'detail-status-failed':'detail-status-pending');var statusText=(d.recvDRStatus==='DELIVRD')?'✅ 成功':((d.recvDRStatus==='UNDELIV'||d.recvDRStatus==='REJECTD')?'❌ 失败':'⏳ 等待');html+='<tr>';\n" +
                "html+='<td style=\"font-family:monospace;font-size:11px;\" title=\"'+escapeHtml(d.submitMsgId||'')+'\">'+escapeHtml((d.submitMsgId||'').substring(0,20))+'...</td>';\n" +
                "html+='<td>'+escapeHtml(d.caller||'')+'</td>';\n" +
                "html+='<td>'+escapeHtml(d.callee||'')+'</td>';\n" +
                "html+='<td>'+escapeHtml(d.recvDRTime||'-')+'</td>';\n" +
                "html+='<td class=\"'+statusClass+'\">'+statusText+'</td>';\n" +
                "html+='<td>'+escapeHtml(d.recvDRError||'-')+'</td>';\n" +
                "html+='<td>'+(d.pkNumber||'')+'/'+(d.pkTotal||'')+'</td></tr>';}\n" +
                "tbody.innerHTML=html;}\n" +
                "function renderDetailPagination(){var div=document.getElementById('detailPagination');if(!div||detailTotalPages<=1){div.innerHTML='';return;}var html='<button class=\"page-btn\" onclick=\"goDetailPage(1)\"'+(detailPage===1?'disabled':'')+'>首页</button><button class=\"page-btn\" onclick=\"goDetailPage('+(detailPage-1)+')\"'+(detailPage===1?'disabled':'')+'>上一页</button><span class=\"page-info\">第 '+detailPage+' / '+detailTotalPages+' 页</span><button class=\"page-btn\" onclick=\"goDetailPage('+(detailPage+1)+')\"'+(detailPage===detailTotalPages?'disabled':'')+'>下一页</button><button class=\"page-btn\" onclick=\"goDetailPage('+detailTotalPages+')\"'+(detailPage===detailTotalPages?'disabled':'')+'>尾页</button>';div.innerHTML=html;}\n" +
                "function goDetailPage(p){detailPage=p;loadTaskDetail();}\n" +
                "function closeDetailModal(){document.getElementById('detailModal').style.display='none';currentTaskId='';detailPage=1;}\n" +
                "function exportTask(taskId){if(!taskId){alert('任务ID不能为空');return;}window.open('/api/monitor/queue/task/export?taskId='+encodeURIComponent(taskId)+'&secret=dn17ORUwf36Znl','_blank');}\n" +
                "function deleteTask(taskId){if(!taskId){alert('任务ID不能为空');return;}if(!confirm('确定要取消该任务吗？\\n\\n⚠️ 注意：取消后，该任务状态会标记为已取消，不会再进行发送！')){return;}fetch('/api/monitor/queue/task/delete?taskId='+encodeURIComponent(taskId)+'&secret=dn17ORUwf36Znl',{method:'DELETE'}).then(function(res){return res.json();}).then(function(data){if(data.code===200){alert('✅ '+(data.msg||'删除成功'));loadTasks();}else{alert('❌ 删除失败: '+(data.msg||'未知错误'));}}).catch(function(err){alert('❌ 网络错误: '+err.message);});}\n" +
                "document.addEventListener('click',function(e){var btn=e.target.closest('.view-btn');if(btn){e.stopPropagation();var row=btn.closest('.task-row');if(row&&row.dataset.taskId)showTaskDetail(row.dataset.taskId);}});\n" +
                "document.addEventListener('dblclick',function(e){var row=e.target.closest('.task-row');if(row&&row.dataset.taskId)showTaskDetail(row.dataset.taskId);});\n" +
                "loadRanking();loadTasks();\n" +
                "setInterval(loadRanking,30000);\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }

    private String generateQueueMonitorHtml() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "<title>Texcell ECCOM WEB 队列任务DR监控</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='20' fill='%23667eea'/%3E%3Ctext x='50' y='68' font-size='50' text-anchor='middle' fill='white' font-family='Arial'%3E📊%3C/text%3E%3C/svg%3E\">\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap\" rel=\"stylesheet\">\n" +
                "<style>\n" +
                ":root {\n" +
                "  --bg-primary: #f5f7fa;\n" +
                "  --bg-card: #ffffff;\n" +
                "  --text-primary: #1e293b;\n" +
                "  --text-secondary: #64748b;\n" +
                "  --text-muted: #94a3b8;\n" +
                "  --border-color: #e8ecf1;\n" +
                "  --shadow: 0 1px 3px rgba(0,0,0,0.06);\n" +
                "  --navbar-bg: #ffffff;\n" +
                "  --hover-bg: rgba(0,0,0,0.02);\n" +
                "  --primary-color: #1677ff;\n" +
                "  --primary-hover: #4096ff;\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:#f5f7fa;font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;}\n" +
                ".navbar{background:#ffffff;box-shadow:0 1px 3px rgba(0,0,0,0.06);border-bottom:1px solid #e8ecf1;position:sticky;top:0;z-index:100;}\n" +
                ".nav-container{max-width:2000px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;padding:0 32px;height:70px;}\n" +
                ".logo{display:flex;align-items:center;gap:12px;}\n" +
                ".logo-icon{width:40px;height:40px;background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);border-radius:12px;display:flex;align-items:center;justify-content:center;font-size:20px;}\n" +
                ".logo-text{font-size:18px;font-weight:700;color:#1e293b;letter-spacing:1px;}\n" +
                ".logo-sub{font-size:11px;color:#94a3b8;margin-left:8px;}\n" +
                ".menu{display:flex;gap:12px;}\n" +
                ".menu a{color:#64748b;text-decoration:none;padding:8px 20px;border-radius:40px;transition:all 0.3s;font-weight:500;font-size:14px;}\n" +
                ".menu a:hover{background:#f1f5f9;color:#1e293b;}\n" +
                ".menu a.active{background:#e8f0fe;color:#1677ff;box-shadow:none;}\n" +
                ".user-info{display:flex;align-items:center;gap:20px;}\n" +
                ".user-name{color:#475569;font-size:14px;}\n" +
                ".user-role{background:rgba(22,119,255,0.1);padding:4px 12px;border-radius:20px;font-size:12px;color:#1677ff;}\n" +
                ".logout-btn{background:#f1f5f9;border:1px solid #e8ecf1;color:#475569;padding:8px 20px;border-radius:40px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".page-wrap{max-width:2000px;margin:0 auto;padding:32px;}\n" +
                ".page-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:24px;flex-wrap:wrap;gap:16px;}\n" +
                ".page-title{font-size:24px;font-weight:700;color:#1e293b;display:flex;align-items:center;gap:12px;}\n" +
                ".page-title .badge{background:#ff4d4f;color:white;font-size:12px;padding:2px 12px;border-radius:20px;font-weight:500;}\n" +
                ".page-title .badge.warning{background:#faad14;}\n" +
                ".stats-grid{display:grid;grid-template-columns:repeat(5,1fr);gap:20px;margin-bottom:24px;}\n" +
                ".stat-card{background:#ffffff;border-radius:12px;padding:20px;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);}\n" +
                ".stat-number{font-size:28px;font-weight:700;color:#1677ff;}\n" +
                ".stat-label{font-size:13px;color:#94a3b8;margin-top:4px;}\n" +
                ".stat-sub{font-size:12px;color:#94a3b8;margin-top:2px;}\n" +
                ".search-card{background:#ffffff;border-radius:12px;padding:20px 24px;margin-bottom:24px;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);}\n" +
                ".search-row{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-end;}\n" +
                ".search-group{display:flex;flex-direction:column;gap:6px;}\n" +
                ".search-group label{font-size:12px;color:#64748b;font-weight:500;}\n" +
                ".search-group input,.search-group select{padding:8px 12px;border:1px solid #d1d5db;border-radius:8px;font-size:13px;min-width:150px;transition:all 0.3s;background:#ffffff;color:#1e293b;}\n" +
                ".search-group input:focus,.search-group select:focus{outline:none;border-color:#1677ff;box-shadow:0 0 0 3px rgba(22,119,255,0.1);}\n" +
                ".search-actions{display:flex;gap:10px;margin-left:auto;align-items:center;}\n" +
                ".search-btn{background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);color:white;border:none;padding:8px 20px;border-radius:8px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".search-btn:hover{transform:translateY(-1px);box-shadow:0 4px 12px rgba(22,119,255,0.3);}\n" +
                ".reset-btn{background:#f1f5f9;border:1px solid #d1d5db;padding:8px 20px;border-radius:8px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;color:#64748b;}\n" +
                ".reset-btn:hover{background:#e8ecf1;}\n" +
                ".scan-btn{background:linear-gradient(135deg,#52c41a 0%,#389e0d 100%);color:white;border:none;padding:8px 20px;border-radius:8px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".scan-btn:hover{transform:translateY(-1px);box-shadow:0 4px 12px rgba(82,196,26,0.3);}\n" +
                ".log-card{background:#ffffff;border-radius:12px;overflow:auto;border:1px solid #e8ecf1;box-shadow:0 1px 3px rgba(0,0,0,0.04);}\n" +
                ".log-table{width:100%;border-collapse:collapse;min-width:1200px;}\n" +
                ".log-table th{background:#f8fafc;padding:12px 16px;text-align:left;font-size:12px;font-weight:600;color:#64748b;border-bottom:1px solid #e8ecf1;white-space:nowrap;}\n" +
                ".log-table td{padding:12px 16px;font-size:13px;color:#1e293b;border-bottom:1px solid #e8ecf1;vertical-align:middle;}\n" +
                ".log-table tr:hover td{background:#f8fafc;}\n" +
                ".status-badge{display:inline-block;padding:2px 10px;border-radius:12px;font-size:11px;font-weight:500;}\n" +
                ".status-pending{background:rgba(250,173,20,0.15);color:#faad14;}\n" +
                ".status-processed{background:rgba(82,196,26,0.15);color:#52c41a;}\n" +
                ".status-ignored{background:rgba(148,163,184,0.15);color:#94a3b8;}\n" +
                ".alert-warning{background:rgba(250,173,20,0.15);color:#faad14;}\n" +
                ".alert-critical{background:rgba(255,77,79,0.15);color:#ff4d4f;}\n" +
                ".action-btn{padding:4px 12px;border-radius:6px;cursor:pointer;font-size:12px;transition:all 0.3s;border:none;}\n" +
                ".action-btn.fix-btn{background:#1677ff;color:white;}\n" +
                ".action-btn.fix-btn:hover{background:#4096ff;}\n" +
                ".action-btn.ignore-btn{background:#f1f5f9;color:#64748b;border:1px solid #d1d5db;}\n" +
                ".action-btn.ignore-btn:hover{background:#e8ecf1;}\n" +
                ".action-btn:disabled{opacity:0.5;cursor:not-allowed;}\n" +
                ".pagination{display:flex;justify-content:center;gap:12px;padding:16px;border-top:1px solid #e8ecf1;flex-wrap:wrap;}\n" +
                ".page-btn{padding:6px 14px;border:1px solid #d1d5db;background:#ffffff;color:#1e293b;border-radius:8px;cursor:pointer;font-size:12px;transition:all 0.3s;}\n" +
                ".page-btn:hover:not(:disabled){border-color:#1677ff;color:#1677ff;}\n" +
                ".page-btn:disabled{opacity:0.5;cursor:not-allowed;background:#f1f5f9;}\n" +
                ".page-info{font-size:12px;color:#94a3b8;align-self:center;}\n" +
                ".empty{text-align:center;padding:48px;color:#94a3b8;}\n" +
                ".footer{text-align:center;padding:32px 0;color:#94a3b8;font-size:12px;border-top:1px solid #e8ecf1;margin-top:16px;}\n" +
                ".modal{position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.6);z-index:10000;display:flex;align-items:center;justify-content:center;backdrop-filter:blur(4px);}\n" +
                ".modal-content{background:#ffffff;border-radius:12px;width:550px;max-width:90%;padding:24px;box-shadow:0 20px 60px rgba(0,0,0,0.15);}\n" +
                ".modal-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;padding-bottom:12px;border-bottom:1px solid #e8ecf1;}\n" +
                ".modal-header h3{color:#1e293b;margin:0;}\n" +
                ".modal-close{background:none;border:none;font-size:28px;cursor:pointer;color:#94a3b8;}\n" +
                ".modal-close:hover{color:#1e293b;}\n" +
                ".modal-body{color:#475569;font-size:14px;line-height:1.8;margin-bottom:20px;}\n" +
                ".modal-body .highlight{color:#ff4d4f;font-weight:600;}\n" +
                ".modal-body code{background:#f1f5f9;padding:2px 8px;border-radius:4px;font-size:13px;word-break:break-all;}\n" +
                ".modal-buttons{display:flex;gap:12px;justify-content:flex-end;margin-top:16px;padding-top:16px;border-top:1px solid #e8ecf1;}\n" +
                ".modal-buttons .confirm-btn{background:linear-gradient(135deg,#1677ff 0%,#4096ff 100%);color:white;border:none;padding:8px 24px;border-radius:8px;cursor:pointer;font-weight:500;}\n" +
                ".modal-buttons .confirm-btn:hover{box-shadow:0 4px 12px rgba(22,119,255,0.3);}\n" +
                ".modal-buttons .cancel-btn{background:#f1f5f9;border:1px solid #d1d5db;padding:8px 24px;border-radius:8px;cursor:pointer;color:#64748b;}\n" +
                ".modal-buttons .cancel-btn:hover{background:#e8ecf1;}\n" +
                ".modal-content-wide{width:650px !important;max-width:95% !important;}\n" +
                ".numbers-info{display:flex;gap:24px;flex-wrap:wrap;font-size:13px;color:#475569;margin-bottom:12px;}\n" +
                ".numbers-info code{background:#f1f5f9;padding:2px 8px;border-radius:4px;font-size:12px;word-break:break-all;}\n" +
                ".numbers-info .highlight{color:#ff4d4f;font-weight:600;}\n" +
                ".numbers-table-wrap{border:1px solid #e8ecf1;border-radius:8px;max-height:400px;overflow:auto;}\n" +
                ".numbers-table-wrap table{width:100%;border-collapse:collapse;}\n" +
                ".numbers-table-wrap th{position:sticky;top:0;background:#f8fafc;z-index:1;padding:10px 16px;text-align:left;font-size:12px;font-weight:600;color:#64748b;border-bottom:1px solid #e8ecf1;}\n" +
                ".numbers-table-wrap td{padding:8px 16px;font-size:13px;color:#1e293b;border-bottom:1px solid #e8ecf1;}\n" +
                ".numbers-table-wrap tr:hover td{background:#f8fafc;}\n" +
                ".numbers-pagination{display:flex;justify-content:center;align-items:center;gap:12px;padding:12px 0 0 0;flex-wrap:wrap;}\n" +
                ".numbers-pagination .page-btn{padding:4px 12px;border:1px solid #d1d5db;background:#ffffff;color:#1e293b;border-radius:6px;cursor:pointer;font-size:12px;transition:all 0.3s;}\n" +
                ".numbers-pagination .page-btn:hover:not(:disabled){border-color:#1677ff;color:#1677ff;}\n" +
                ".numbers-pagination .page-btn:disabled{opacity:0.5;cursor:not-allowed;background:#f1f5f9;}\n" +
                ".numbers-pagination .page-info{font-size:12px;color:#94a3b8;}\n" +
                ".numbers-pagination .total-info{font-size:12px;color:#94a3b8;margin-right:8px;}\n" +
                "@media(max-width:1024px){.stats-grid{grid-template-columns:repeat(3,1fr);}}\n" +
                "@media(max-width:768px){.stats-grid{grid-template-columns:repeat(2,1fr);}.search-actions{margin-left:0;flex-wrap:wrap;}.nav-container{padding:0 20px;}.menu a{padding:6px 12px;font-size:12px;}.page-wrap{padding:20px;}.modal-content-wide{width:95% !important;}}\n" +
                "@media(max-width:480px){.stats-grid{grid-template-columns:1fr;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"navbar\">\n" +
                "<div class=\"nav-container\">\n" +
                "<div class=\"logo\">\n" +
                "<div class=\"logo-icon\">📊</div>\n" +
                "<div class=\"logo-text\">ECCOM WEB 监控管理系统</div>\n" +
                "<div class=\"logo-sub\">v3.0</div>\n" +
                "</div>\n" +
                "<div class=\"menu\">\n" +
                "<a href=\"/monitor/index\">首页</a>\n" +
                "<a href=\"/monitor/dashboard\">服务监控</a>\n" +
                "<a href=\"/monitor/login-audit\">登录审计</a>\n" +
                "<a href=\"/monitor/number-files\">号码备份</a>\n" +
                "<a href=\"/monitor/customer-management\">API客户管理</a>\n" +
                "<a href=\"/monitor/user-management\">登录账户管理</a>\n" +
                "<a href=\"/monitor/ops-management\">运维管理</a>\n" +
                "<a href=\"/monitor/queue-management\">发送队列管理</a>\n" +
                "<a href=\"/monitor/queue-monitor\" class=\"active\">队列DR监控</a>\n" +
                "</div>\n" +
                "<div class=\"user-info\">\n" +
                "<span class=\"user-name\" id=\"currentUsername\">加载中...</span>\n" +
                "<span class=\"user-role\" id=\"currentUserRole\">加载中...</span>\n" +
                "<button class=\"logout-btn\" onclick=\"logout()\">退出</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"page-wrap\">\n" +
                "<div class=\"page-header\">\n" +
                "<div class=\"page-title\">\n" +
                "📨 队列任务DR监控\n" +
                "<span class=\"badge warning\" id=\"pendingBadge\">加载中...</span>\n" +
                "</div>\n" +
                "<button class=\"scan-btn\" onclick=\"manualScan()\">🔄 手动扫描</button>\n" +
                "</div>\n" +
                "<div class=\"stats-grid\" id=\"statsGrid\">\n" +
                "<div class=\"stat-card\"><div class=\"stat-number\" id=\"statPending\">-</div><div class=\"stat-label\">待处理告警</div></div>\n" +
                "<div class=\"stat-card\"><div class=\"stat-number\" id=\"statWarning\">-</div><div class=\"stat-label\">本周告警</div></div>\n" +
                "<div class=\"stat-card\"><div class=\"stat-number\" id=\"statCritical\">-</div><div class=\"stat-label\">较上周</div></div>\n" +
                "<div class=\"stat-card\"><div class=\"stat-number\" id=\"statProcessed\">-</div><div class=\"stat-label\">本月已处理</div></div>\n" +
                "<div class=\"stat-card\"><div class=\"stat-number\" id=\"statAvgTime\">-</div><div class=\"stat-label\">平均处理时长</div></div>\n" +
                "</div>\n" +
                "<div class=\"search-card\">\n" +
                "<div class=\"search-row\">\n" +
                "<div class=\"search-group\"><label>🆔 任务ID</label><input type=\"text\" id=\"searchTaskId\" placeholder=\"模糊查询\"></div>\n" +
                "<div class=\"search-group\"><label>👤 客户账号</label><input type=\"text\" id=\"searchSubAccount\" placeholder=\"模糊查询\"></div>\n" +
                "<div class=\"search-group\"><label>📊 状态</label><select id=\"searchStatus\"><option value=\"\">全部</option><option value=\"PENDING\">待处理</option><option value=\"PROCESSED\">已处理</option><option value=\"IGNORED\">已忽略</option></select></div>\n" +
                "<div class=\"search-group\"><label>🔔 告警级别</label><select id=\"searchAlertLevel\"><option value=\"\">全部</option><option value=\"WARNING\">警告(≥30分钟)</option><option value=\"CRITICAL\">严重(≥60分钟)</option></select></div>\n" +
                "<div class=\"search-actions\">\n" +
                "<button class=\"search-btn\" onclick=\"searchLogs()\">🔍 查询</button>\n" +
                "<button class=\"reset-btn\" onclick=\"resetSearch()\">🔄 重置</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"log-card\">\n" +
                "<table class=\"log-table\">\n" +
                "<thead>\n" +
                "<tr>\n" +
                "<th>任务ID</th>\n" +
                "<th>客户账号</th>\n" +
                "<th>任务名称</th>\n" +
                "<th>总发送数</th>\n" +
                "<th>已提交</th>\n" +
                "<th>成功</th>\n" +
                "<th>失败</th>\n" +
                "<th>任务时间</th>\n" +
                "<th>DR未更新数</th>\n" +
                "<th>已过时间</th>\n" +
                "<th>告警级别</th>\n" +
                "<th>状态</th>\n" +
                "<th>操作</th>\n" +
                "</tr>\n" +
                "</thead>\n" +
                "<tbody id=\"logBody\"><tr><td colspan=\"13\" class=\"empty\">加载中...</td></tr></tbody>\n" +
                "</table>\n" +
                "<div class=\"pagination\" id=\"pagination\"></div>\n" +
                "</div>\n" +
                "<div class=\"footer\">📨 队列任务DR监控 | 任务已完成但DR未更新 | 每10分钟自动扫描</div>\n" +
                "</div>\n" +
                "<!-- 确认弹窗 -->\n" +
                "<div id=\"confirmModal\" class=\"modal\" style=\"display:none;\">\n" +
                "<div class=\"modal-content\">\n" +
                "<div class=\"modal-header\">\n" +
                "<h3>⚠️ 确认执行修复</h3>\n" +
                "<button class=\"modal-close\" onclick=\"closeModal()\">&times;</button>\n" +
                "</div>\n" +
                "<div class=\"modal-body\" id=\"modalBody\">\n" +
                "<p>确认对以下任务执行修复？</p>\n" +
                "<p><strong>任务ID：</strong><code id=\"modalTaskId\">-</code></p>\n" +
                "<p><strong>客户账号：</strong><span id=\"modalSubAccount\">-</span></p>\n" +
                "<p><strong>总发送数：</strong><span id=\"modalTotalCount\">-</span></p>\n" +
                "<p><strong>DR未更新数：</strong><span class=\"highlight\" id=\"modalPendingCount\">-</span></p>\n" +
                "<p style=\"color:#faad14;margin-top:12px;\">📌 请确认此任务的短信是否已经全部发送出去！</p>\n" +
                "<p style=\"color:#ff4d4f;\">⚠️ 执行后会将 <code>RecvDRStatus</code> 更新为 <code>DELIVRD</code>，<code>RecvDRError</code> 更新为 <code>000</code></p>\n" +
                "</div>\n" +
                "<div class=\"modal-buttons\">\n" +
                "<button class=\"confirm-btn\" onclick=\"confirmFix()\">✅ 确认执行</button>\n" +
                "<button class=\"cancel-btn\" onclick=\"closeModal()\">取消</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<!-- 查看未更新号码弹窗 -->\n" +
                "<div id=\"numbersModal\" class=\"modal\" style=\"display:none;\">\n" +
                "<div class=\"modal-content modal-content-wide\">\n" +
                "<div class=\"modal-header\">\n" +
                "<h3>未更新DR号码列表</h3>\n" +
                "<button class=\"modal-close\" onclick=\"closeNumbersModal()\">&times;</button>\n" +
                "</div>\n" +
                "<div class=\"modal-body\" id=\"numbersModalBody\">\n" +
                "<div class=\"numbers-info\">\n" +
                "<span><strong>任务ID：</strong><code id=\"numbersTaskId\">-</code></span>\n" +
                "<span><strong>未更新数：</strong><span class=\"highlight\" id=\"numbersTotalCount\">0</span></span>\n" +
                "</div>\n" +
                "<div class=\"numbers-table-wrap\">\n" +
                "<table>\n" +
                "<thead>\n" +
                "<tr>\n" +
                "<th style=\"width:60px;text-align:center;\">序号</th>\n" +
                "<th>手机号码</th>\n" +
                "</tr>\n" +
                "</thead>\n" +
                "<tbody id=\"numbersBody\">\n" +
                "<tr><td colspan=\"2\" style=\"padding:30px;text-align:center;color:#94a3b8;\">加载中...</td></tr>\n" +
                "</tbody>\n" +
                "</table>\n" +
                "</div>\n" +
                "<div class=\"numbers-pagination\" id=\"numbersPagination\"></div>\n" +
                "</div>\n" +
                "<div class=\"modal-buttons\" style=\"border-top:none;padding-top:0;margin-top:0;\">\n" +
                "<button class=\"cancel-btn\" onclick=\"closeNumbersModal()\">关闭</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<script>\n" +
                "var page = 1, size = 20, totalPages = 1;\n" +
                "var currentTaskId = null;\n" +
                "var numbersTaskId = null;\n" +
                "var numbersPage = 1;\n" +
                "var numbersSize = 10;\n" +
                "var numbersTotalPages = 1;\n" +
                "\n" +
                "function logout(){ fetch('/monitor/logout').then(function(){ location.href = '/monitor/login'; }); }\n" +
                "\n" +
                "function getCurrentUserInfo() {\n" +
                "    fetch('/api/monitor/current-user?secret=dn17ORUwf36Znl')\n" +
                "        .then(function(res) { return res.json(); })\n" +
                "        .then(function(data) {\n" +
                "            if (data.code === 200) {\n" +
                "                document.getElementById('currentUsername').innerText = data.data.username;\n" +
                "                var roleSpan = document.getElementById('currentUserRole');\n" +
                "                var roleText = data.data.role === 'ADMIN' ? '👑 管理员' : '🔧 NOC';\n" +
                "                roleSpan.innerText = roleText;\n" +
                "            }\n" +
                "        }).catch(function(err) { console.error('获取用户信息失败:', err); });\n" +
                "}\n" +
                "getCurrentUserInfo();\n" +
                "\n" +
                "function loadStats() {\n" +
                "    fetch('/api/monitor/queue-monitor/stats?secret=dn17ORUwf36Znl')\n" +
                "        .then(function(res) { return res.json(); })\n" +
                "        .then(function(data) {\n" +
                "            if (data.code === 200) {\n" +
                "                var d = data.data;\n" +
                "                document.getElementById('statPending').innerText = d.pendingCount || 0;\n" +
                "                document.getElementById('pendingBadge').innerHTML = '待处理 ' + (d.pendingCount || 0);\n" +
                "                var weekCompare = d.weekCompare || {};\n" +
                "                document.getElementById('statWarning').innerText = weekCompare.this_week_count || 0;\n" +
                "                var lastWeek = weekCompare.last_week_count || 0;\n" +
                "                var thisWeek = weekCompare.this_week_count || 0;\n" +
                "                var trend = thisWeek - lastWeek;\n" +
                "                var trendText = trend > 0 ? '↑ +' + trend : (trend < 0 ? '↓ ' + trend : '持平');\n" +
                "                document.getElementById('statCritical').innerText = trendText;\n" +
                "                var monthStats = d.monthStatsByStatus || [];\n" +
                "                var processed = 0;\n" +
                "                for (var i = 0; i < monthStats.length; i++) {\n" +
                "                    if (monthStats[i].status === 'PROCESSED') processed = monthStats[i].count;\n" +
                "                }\n" +
                "                document.getElementById('statProcessed').innerText = processed;\n" +
                "                var avgTime = d.avgHandleTime;\n" +
                "                var avgText = avgTime ? Math.round(avgTime.avg_handle_minutes) + '分钟' : '-';\n" +
                "                document.getElementById('statAvgTime').innerText = avgText;\n" +
                "            }\n" +
                "        }).catch(function(err) { console.error('加载统计失败:', err); });\n" +
                "}\n" +
                "\n" +
                "function searchLogs() { page = 1; loadLogs(); }\n" +
                "\n" +
                "function resetSearch() {\n" +
                "    document.getElementById('searchTaskId').value = '';\n" +
                "    document.getElementById('searchSubAccount').value = '';\n" +
                "    document.getElementById('searchStatus').value = '';\n" +
                "    document.getElementById('searchAlertLevel').value = '';\n" +
                "    page = 1;\n" +
                "    loadLogs();\n" +
                "}\n" +
                "\n" +
                "function loadLogs() {\n" +
                "    var taskId = document.getElementById('searchTaskId').value;\n" +
                "    var subAccount = document.getElementById('searchSubAccount').value;\n" +
                "    var status = document.getElementById('searchStatus').value;\n" +
                "    var alertLevel = document.getElementById('searchAlertLevel').value;\n" +
                "    var url = '/api/monitor/queue-monitor/list?secret=dn17ORUwf36Znl&page=' + page + '&size=' + size;\n" +
                "    if (taskId) url += '&taskId=' + encodeURIComponent(taskId);\n" +
                "    if (subAccount) url += '&subAccount=' + encodeURIComponent(subAccount);\n" +
                "    if (status) url += '&status=' + status;\n" +
                "    if (alertLevel) url += '&alertLevel=' + alertLevel;\n" +
                "    fetch(url).then(function(res) { return res.json(); })\n" +
                "        .then(function(data) {\n" +
                "            if (data.code === 200) {\n" +
                "                renderTable(data.data || []);\n" +
                "                totalPages = data.totalPages || 1;\n" +
                "                renderPagination();\n" +
                "            } else {\n" +
                "                document.getElementById('logBody').innerHTML = '<tr><td colspan=\"13\" class=\"empty\">加载失败: ' + (data.msg || '错误') + '</td></tr>';\n" +
                "            }\n" +
                "        }).catch(function() {\n" +
                "            document.getElementById('logBody').innerHTML = '<tr><td colspan=\"13\" class=\"empty\">加载失败: 网络错误</td></tr>';\n" +
                "        });\n" +
                "}\n" +
                "\n" +
                "function renderTable(list) {\n" +
                "    var tbody = document.getElementById('logBody');\n" +
                "    if (!tbody) return;\n" +
                "    if (!list || list.length === 0) {\n" +
                "        tbody.innerHTML = '<tr><td colspan=\"13\" class=\"empty\">暂无数据</td></tr>';\n" +
                "        return;\n" +
                "    }\n" +
                "    var html = '';\n" +
                "    var statusMap = { 'PENDING': '待处理', 'PROCESSED': '已处理', 'IGNORED': '已忽略' };\n" +
                "    var statusClassMap = { 'PENDING': 'status-pending', 'PROCESSED': 'status-processed', 'IGNORED': 'status-ignored' };\n" +
                "    var alertMap = { 'WARNING': '警告', 'CRITICAL': '严重' };\n" +
                "    var alertClassMap = { 'WARNING': 'alert-warning', 'CRITICAL': 'alert-critical' };\n" +
                "    for (var i = 0; i < list.length; i++) {\n" +
                "        var log = list[i];\n" +
                "        var canFix = log.status === 'PENDING';\n" +
                "        var canIgnore = log.status === 'PENDING';\n" +
                "        var pendingCount = log.detailPendingCount || 0;\n" +
                "        html += '<tr>' +\n" +
                "            '<td style=\"font-family:monospace;font-size:12px;\" title=\"' + escapeHtml(log.taskId) + '\">' + escapeHtml((log.taskId || '').substring(0, 25)) + '...</td>' +\n" +
                "            '<td>' + escapeHtml(log.subAccount || '-') + '</td>' +\n" +
                "            '<td>' + escapeHtml((log.taskName || '-').substring(0, 30)) + '</td>' +\n" +
                "            '<td>' + (log.totalCount || 0) + '</td>' +\n" +
                "            '<td>' + (log.submitSuccCount || 0) + '</td>' +\n" +
                "            '<td>' + (log.drSuccCount || 0) + '</td>' +\n" +
                "            '<td>' + (log.drFailCount || 0) + '</td>' +\n" +
                "            '<td>' + (log.taskTime ? formatDateTime(log.taskTime) : '-') + '</td>' +\n" +
                "            '<td>' +\n" +
                "                (pendingCount > 0 \n" +
                "                    ? '<a href=\"javascript:void(0)\" style=\"color:#ff4d4f;font-weight:600;text-decoration:underline;cursor:pointer;\" onclick=\"showPendingNumbers(\\'' + escapeHtml(log.taskId) + '\\', ' + pendingCount + ')\">' + pendingCount + '</a>' \n" +
                "                    : '<span style=\"color:#94a3b8;\">0</span>'\n" +
                "                ) +\n" +
                "            '</td>' +\n" +
                "            '<td>' + (log.elapsedMinutes || 0) + '分钟</td>' +\n" +
                "            '<td><span class=\"status-badge ' + alertClassMap[log.alertLevel] + '\">' + alertMap[log.alertLevel] + '</span></td>' +\n" +
                "            '<td><span class=\"status-badge ' + statusClassMap[log.status] + '\">' + statusMap[log.status] + '</span></td>' +\n" +
                "            '<td>';\n" +
                "        if (canFix) {\n" +
                "            html += '<button class=\"action-btn fix-btn\" onclick=\"showConfirmModal(\\'' + escapeHtml(log.taskId) + '\\', \\'' + escapeHtml(log.subAccount || '-') + '\\', ' + (log.totalCount || 0) + ', ' + pendingCount + ')\">修复</button> ';\n" +
                "        }\n" +
                "        if (canIgnore) {\n" +
                "            html += '<button class=\"action-btn ignore-btn\" onclick=\"ignoreTask(\\'' + escapeHtml(log.taskId) + '\\')\">忽略</button>';\n" +
                "        }\n" +
                "        if (!canFix && !canIgnore) {\n" +
                "            html += '-';\n" +
                "        }\n" +
                "        html += '</td></tr>';\n" +
                "    }\n" +
                "    tbody.innerHTML = html;\n" +
                "}\n" +
                "\n" +
                "function renderPagination() {\n" +
                "    var div = document.getElementById('pagination');\n" +
                "    if (!div) return;\n" +
                "    if (totalPages <= 1) { div.innerHTML = ''; return; }\n" +
                "    var html = '<button class=\"page-btn\" onclick=\"goPage(1)\"' + (page === 1 ? 'disabled' : '') + '>首页</button>' +\n" +
                "        '<button class=\"page-btn\" onclick=\"goPage(' + (page - 1) + ')\"' + (page === 1 ? 'disabled' : '') + '>上一页</button>' +\n" +
                "        '<span class=\"page-info\">第 ' + page + ' / ' + totalPages + ' 页</span>' +\n" +
                "        '<button class=\"page-btn\" onclick=\"goPage(' + (page + 1) + ')\"' + (page === totalPages ? 'disabled' : '') + '>下一页</button>' +\n" +
                "        '<button class=\"page-btn\" onclick=\"goPage(' + totalPages + ')\"' + (page === totalPages ? 'disabled' : '') + '>尾页</button>';\n" +
                "    div.innerHTML = html;\n" +
                "}\n" +
                "\n" +
                "function goPage(p) { page = p; loadLogs(); }\n" +
                "\n" +
                "function showConfirmModal(taskId, subAccount, totalCount, pendingCount) {\n" +
                "    currentTaskId = taskId;\n" +
                "    document.getElementById('modalTaskId').innerText = taskId;\n" +
                "    document.getElementById('modalSubAccount').innerText = subAccount;\n" +
                "    document.getElementById('modalTotalCount').innerText = totalCount;\n" +
                "    document.getElementById('modalPendingCount').innerText = pendingCount;\n" +
                "    document.getElementById('confirmModal').style.display = 'flex';\n" +
                "}\n" +
                "\n" +
                "function closeModal() {\n" +
                "    document.getElementById('confirmModal').style.display = 'none';\n" +
                "    currentTaskId = null;\n" +
                "}\n" +
                "\n" +
                "function formatDateTime(dateStr) {\n" +
                "    if (!dateStr) return '-';\n" +
                "    return dateStr.replace('T', ' ').substring(0, 19);\n" +
                "}\n" +
                "\n" +
                "function confirmFix() {\n" +
                "    if (!currentTaskId) return;\n" +
                "    var btn = document.querySelector('.modal-buttons .confirm-btn');\n" +
                "    btn.disabled = true;\n" +
                "    btn.innerText = '执行中...';\n" +
                "    fetch('/api/monitor/queue-monitor/fix?secret=dn17ORUwf36Znl', {\n" +
                "        method: 'POST',\n" +
                "        headers: { 'Content-Type': 'application/json' },\n" +
                "        body: JSON.stringify({ taskId: currentTaskId })\n" +
                "    }).then(function(res) { return res.json(); })\n" +
                "      .then(function(data) {\n" +
                "          btn.disabled = false;\n" +
                "          btn.innerText = '确认执行';\n" +
                "          if (data.code === 200) {\n" +
                "              alert('✅ 修复成功！更新了 ' + data.updated + ' 条记录');\n" +
                "              closeModal();\n" +
                "              loadLogs();\n" +
                "              loadStats();\n" +
                "          } else {\n" +
                "              alert('❌ 修复失败: ' + (data.msg || '未知错误'));\n" +
                "          }\n" +
                "      }).catch(function(err) {\n" +
                "          btn.disabled = false;\n" +
                "          btn.innerText = '确认执行';\n" +
                "          alert('❌ 网络错误: ' + err.message);\n" +
                "      });\n" +
                "}\n" +
                "\n" +
                "function ignoreTask(taskId) {\n" +
                "    if (!confirm('确定要忽略该告警吗？')) return;\n" +
                "    fetch('/api/monitor/queue-monitor/ignore?secret=dn17ORUwf36Znl', {\n" +
                "        method: 'POST',\n" +
                "        headers: { 'Content-Type': 'application/json' },\n" +
                "        body: JSON.stringify({ taskId: taskId })\n" +
                "    }).then(function(res) { return res.json(); })\n" +
                "      .then(function(data) {\n" +
                "          if (data.code === 200) {\n" +
                "              alert('已忽略该告警');\n" +
                "              loadLogs();\n" +
                "              loadStats();\n" +
                "          } else {\n" +
                "              alert('操作失败: ' + (data.msg || '未知错误'));\n" +
                "          }\n" +
                "      }).catch(function(err) {\n" +
                "          alert('网络错误: ' + err.message);\n" +
                "      });\n" +
                "}\n" +
                "\n" +
                "function manualScan() {\n" +
                "    if (!confirm('确定要手动扫描超时未更新DR的队列任务吗？')) return;\n" +
                "    var btn = document.querySelector('.scan-btn');\n" +
                "    btn.disabled = true;\n" +
                "    btn.innerText = '扫描中...';\n" +
                "    fetch('/api/monitor/queue-monitor/scan?secret=dn17ORUwf36Znl', { method: 'POST' })\n" +
                "        .then(function(res) { return res.json(); })\n" +
                "        .then(function(data) {\n" +
                "            btn.disabled = false;\n" +
                "            btn.innerText = '🔄 手动扫描';\n" +
                "            if (data.code === 200) {\n" +
                "                alert(data.msg);\n" +
                "                loadLogs();\n" +
                "                loadStats();\n" +
                "            } else {\n" +
                "                alert('扫描失败: ' + (data.msg || '未知错误'));\n" +
                "            }\n" +
                "        }).catch(function(err) {\n" +
                "            btn.disabled = false;\n" +
                "            btn.innerText = '🔄 手动扫描';\n" +
                "            alert('网络错误: ' + err.message);\n" +
                "        });\n" +
                "}\n" +
                "\n" +
                "function escapeHtml(s) {\n" +
                "    if (!s) return '';\n" +
                "    return String(s).replace(/[&<>]/g, function(m) {\n" +
                "        if (m === '&') return '&amp;';\n" +
                "        if (m === '<') return '&lt;';\n" +
                "        if (m === '>') return '&gt;';\n" +
                "        return m;\n" +
                "    });\n" +
                "}\n" +
                "\n" +
                "function showPendingNumbers(taskId, totalCount) {\n" +
                "    numbersTaskId = taskId;\n" +
                "    numbersPage = 1;\n" +
                "    document.getElementById('numbersTaskId').innerText = taskId;\n" +
                "    document.getElementById('numbersTotalCount').innerText = totalCount || 0;\n" +
                "    document.getElementById('numbersModal').style.display = 'flex';\n" +
                "    loadNumbers();\n" +
                "}\n" +
                "\n" +
                "function closeNumbersModal() {\n" +
                "    document.getElementById('numbersModal').style.display = 'none';\n" +
                "    numbersTaskId = null;\n" +
                "}\n" +
                "\n" +
                "function loadNumbers() {\n" +
                "    if (!numbersTaskId) return;\n" +
                "    var url = '/api/monitor/queue-monitor/pending-numbers?secret=dn17ORUwf36Znl&taskId=' + encodeURIComponent(numbersTaskId) + '&page=' + numbersPage + '&size=' + numbersSize;\n" +
                "    fetch(url)\n" +
                "        .then(function(res) { return res.json(); })\n" +
                "        .then(function(data) {\n" +
                "            if (data.code === 200) {\n" +
                "                renderNumbers(data.data || [], data.total || 0, data.totalPages || 1);\n" +
                "            } else {\n" +
                "                document.getElementById('numbersBody').innerHTML = '<tr><td colspan=\"2\" style=\"padding:30px;text-align:center;color:#ff4d4f;\">加载失败: ' + (data.msg || '错误') + '</td></tr>';\n" +
                "            }\n" +
                "        })\n" +
                "        .catch(function() {\n" +
                "            document.getElementById('numbersBody').innerHTML = '<tr><td colspan=\"2\" style=\"padding:30px;text-align:center;color:#ff4d4f;\">加载失败: 网络错误</td></tr>';\n" +
                "        });\n" +
                "}\n" +
                "\n" +
                "function renderNumbers(list, total, totalPages) {\n" +
                "    var tbody = document.getElementById('numbersBody');\n" +
                "    if (!tbody) return;\n" +
                "    if (!list || list.length === 0) {\n" +
                "        tbody.innerHTML = '<tr><td colspan=\"2\" style=\"padding:30px;text-align:center;color:#94a3b8;\">暂无数据</td></tr>';\n" +
                "        renderNumbersPagination(0, totalPages);\n" +
                "        return;\n" +
                "    }\n" +
                "    var html = '';\n" +
                "    var startIndex = (numbersPage - 1) * numbersSize + 1;\n" +
                "    for (var i = 0; i < list.length; i++) {\n" +
                "        var item = list[i];\n" +
                "        var mobileNumber = item.mobileNumber || '-';\n" +
                "        html += '<tr>' +\n" +
                "            '<td style=\"text-align:center;color:#94a3b8;font-size:12px;\">' + (startIndex + i) + '</td>' +\n" +
                "            '<td style=\"font-family:monospace;font-size:13px;\">' + escapeHtml(mobileNumber) + '</td>' +\n" +
                "            '</tr>';\n" +
                "    }\n" +
                "    tbody.innerHTML = html;\n" +
                "    numbersTotalPages = totalPages || 1;\n" +
                "    renderNumbersPagination(total, totalPages);\n" +
                "}\n" +
                "\n" +
                "function renderNumbersPagination(total, totalPages) {\n" +
                "    var div = document.getElementById('numbersPagination');\n" +
                "    if (!div) return;\n" +
                "    var totalCount = document.getElementById('numbersTotalCount').innerText || 0;\n" +
                "    if (!totalPages || totalPages <= 1) {\n" +
                "        div.innerHTML = '<span class=\"total-info\">共 ' + totalCount + ' 条</span>';\n" +
                "        return;\n" +
                "    }\n" +
                "    var html = '<span class=\"total-info\">共 ' + totalCount + ' 条</span>';\n" +
                "    html += '<button class=\"page-btn\" onclick=\"goNumbersPage(1)\"' + (numbersPage === 1 ? ' disabled' : '') + '>首页</button>';\n" +
                "    html += '<button class=\"page-btn\" onclick=\"goNumbersPage(' + (numbersPage - 1) + ')\"' + (numbersPage === 1 ? ' disabled' : '') + '>上一页</button>';\n" +
                "    html += '<span class=\"page-info\">第 ' + numbersPage + ' / ' + totalPages + ' 页</span>';\n" +
                "    html += '<button class=\"page-btn\" onclick=\"goNumbersPage(' + (numbersPage + 1) + ')\"' + (numbersPage === totalPages ? ' disabled' : '') + '>下一页</button>';\n" +
                "    html += '<button class=\"page-btn\" onclick=\"goNumbersPage(' + totalPages + ')\"' + (numbersPage === totalPages ? ' disabled' : '') + '>尾页</button>';\n" +
                "    div.innerHTML = html;\n" +
                "}\n" +
                "\n" +
                "function goNumbersPage(page) {\n" +
                "    numbersPage = page;\n" +
                "    loadNumbers();\n" +
                "}\n" +
                "\n" +
                "document.addEventListener('click', function(e) {\n" +
                "    var modal = document.getElementById('numbersModal');\n" +
                "    if (e.target === modal) {\n" +
                "        closeNumbersModal();\n" +
                "    }\n" +
                "});\n" +
                "\n" +
                "loadStats();\n" +
                "loadLogs();\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }
}