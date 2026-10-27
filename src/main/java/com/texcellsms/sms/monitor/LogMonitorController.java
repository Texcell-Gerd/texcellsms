package com.texcellsms.sms.monitor;


import CacheDB.NCacheDBLink;
import com.texcellsms.sms.interceptor.SecurityMonitorInterceptor;
import com.texcellsms.sms.mapper.webportal.*;
import com.texcellsms.sms.model.entity.*;
import com.texcellsms.sms.service.*;
import com.texcellsms.sms.util.ApiClientConfig;
import com.texcellsms.sms.util.IpLocationUtil;
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

    @Autowired
    private IpRateLimitConfigService ipRateLimitConfigService;

    @Autowired
    private SecurityMonitorInterceptor securityMonitorInterceptor;

    @Autowired
    private CustomerDrAutoFixService customerDrAutoFixService;

    @Autowired
    private FeatureService featureService;

    @Autowired
    private SubTaskService subTaskService;


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

    @GetMapping("/monitor/dr-auto-fix")
    public String drAutoFix(HttpServletRequest request) {
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            return "<h1 style='color:red;text-align:center;margin-top:100px;'>❌ 仅允许内网访问<br/>您的IP：" + clientIp + "</h1>";
        }
        if (!isLogin(session)) {
            return "<script>location.href='/monitor/login';</script>";
        }
        return generateDrAutoFixHtml();
    }

    /**
     * 管理端页面路由
     */
    @GetMapping("/monitor/subtask-management")
    public String subtaskManagementPage(HttpServletRequest request) {
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            return "<h1 style='color:red;text-align:center;margin-top:100px;'>❌ 仅允许内网访问<br/>您的IP：" + clientIp + "</h1>";
        }
        return generateSubTaskManagementHtml();
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

    /**
     * 获取所有限流配置
     */
    @GetMapping("/api/monitor/rate-limit/list")
    public Map<String, Object> rateLimitList(@RequestParam String secret,
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
            List<IpRateLimitConfig> list = ipRateLimitConfigService.listAll();
            result.put("code", 200);
            result.put("data", list);
            result.put("defaultRateLimit", securityMonitorInterceptor.getDefaultRateLimit());
            result.put("defaultBlockDuration", securityMonitorInterceptor.getDefaultBlockDuration());
        } catch (Exception e) {
            logger.error("获取限流配置列表失败", e);
            result.put("code", 500);
            result.put("msg", "查询失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 新增/修改限流配置
     */
    @PostMapping("/api/monitor/rate-limit/save")
    public Map<String, Object> rateLimitSave(@RequestBody IpRateLimitConfig config,
                                             @RequestParam String secret,
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

        // 校验参数
        if (config.getIp() == null || config.getIp().trim().isEmpty()) {
            result.put("code", 400);
            result.put("msg", "IP地址不能为空");
            return result;
        }
        if (config.getRateLimit() == null || config.getRateLimit() < 1) {
            result.put("code", 400);
            result.put("msg", "限流值必须大于0");
            return result;
        }
        if (config.getBlockDuration() == null || config.getBlockDuration() < 1) {
            result.put("code", 400);
            result.put("msg", "封禁时长必须大于0");
            return result;
        }

        try {
            ipRateLimitConfigService.saveConfig(config, loginUsername);
            // 刷新拦截器缓存
            securityMonitorInterceptor.refreshConfig();

            result.put("code", 200);
            result.put("msg", "保存成功");
            logger.info("运维操作: 保存限流配置, 操作人={}, IP={}, 限流={}/分钟",
                    loginUsername, config.getIp(), config.getRateLimit());
        } catch (Exception e) {
            logger.error("保存限流配置失败", e);
            result.put("code", 500);
            result.put("msg", "保存失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 删除限流配置
     */
    @PostMapping("/api/monitor/rate-limit/delete")
    public Map<String, Object> rateLimitDelete(@RequestParam Long id,
                                               @RequestParam String secret,
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
            ipRateLimitConfigService.deleteConfig(id, loginUsername);
            securityMonitorInterceptor.refreshConfig();

            result.put("code", 200);
            result.put("msg", "删除成功");
            logger.info("运维操作: 删除限流配置, 操作人={}, ID={}", loginUsername, id);
        } catch (Exception e) {
            logger.error("删除限流配置失败", e);
            result.put("code", 500);
            result.put("msg", "删除失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 启用/禁用限流配置
     */
    @PostMapping("/api/monitor/rate-limit/toggle")
    public Map<String, Object> rateLimitToggle(@RequestParam Long id,
                                               @RequestParam Integer enabled,
                                               @RequestParam String secret,
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
            ipRateLimitConfigService.toggleConfig(id, enabled, loginUsername);
            securityMonitorInterceptor.refreshConfig();

            String status = enabled == 1 ? "启用" : "禁用";
            result.put("code", 200);
            result.put("msg", status + "成功");
            logger.info("运维操作: {}限流配置, 操作人={}, ID={}", status, loginUsername, id);
        } catch (Exception e) {
            logger.error("切换限流配置状态失败", e);
            result.put("code", 500);
            result.put("msg", "操作失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 手动解封IP
     */
    @PostMapping("/api/monitor/rate-limit/unblock")
    public Map<String, Object> rateLimitUnblock(@RequestParam String ip,
                                                @RequestParam String secret,
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
            // 解封IP
            securityMonitorInterceptor.unblockIp(ip);
            // 记录操作日志
            ipRateLimitConfigService.unblockIp(ip, loginUsername);

            result.put("code", 200);
            result.put("msg", "IP " + ip + " 已解封");
            logger.info("运维操作: 手动解封IP, 操作人={}, IP={}", loginUsername, ip);
        } catch (Exception e) {
            logger.error("解封IP失败", e);
            result.put("code", 500);
            result.put("msg", "解封失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 获取当前所有被封禁的IP列表
     */
    @GetMapping("/api/monitor/rate-limit/blocked-list")
    public Map<String, Object> getBlockedList(@RequestParam String secret,
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
            Set<String> blockedIps = securityMonitorInterceptor.getBlockedIps();
            List<Map<String, Object>> list = new ArrayList<>();

            for (String ip : blockedIps) {
                Long blockTime = securityMonitorInterceptor.getBlockTime(ip);
                long remainingSeconds = 0;
                if (blockTime != null) {
                    long duration = securityMonitorInterceptor.getIpBlockDurationMs(ip);
                    long elapsed = System.currentTimeMillis() - blockTime;
                    remainingSeconds = Math.max(0, (duration - elapsed) / 1000);
                }
                Map<String, Object> info = new HashMap<>();
                info.put("ip", ip);
                info.put("remainingSeconds", remainingSeconds);
                list.add(info);
            }

            // 按剩余时间排序（少的在前面）
            list.sort((a, b) -> {
                long ra = (long) a.get("remainingSeconds");
                long rb = (long) b.get("remainingSeconds");
                return Long.compare(ra, rb);
            });

            result.put("code", 200);
            result.put("data", list);
        } catch (Exception e) {
            logger.error("获取封禁列表失败", e);
            result.put("code", 500);
            result.put("msg", "查询失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 全部解封
     */
    @PostMapping("/api/monitor/rate-limit/unblock-all")
    public Map<String, Object> unblockAll(@RequestParam String secret,
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
            Set<String> blockedIps = securityMonitorInterceptor.getBlockedIps();
            int count = blockedIps.size();

            for (String ip : blockedIps) {
                securityMonitorInterceptor.unblockIp(ip);
                ipRateLimitConfigService.unblockIp(ip, loginUsername);
            }

            result.put("code", 200);
            result.put("msg", "已解封所有IP，共 " + count + " 个");
            logger.info("运维操作: 全部解封, 操作人={}, 数量={}", loginUsername, count);
        } catch (Exception e) {
            logger.error("全部解封失败", e);
            result.put("code", 500);
            result.put("msg", "操作失败: " + e.getMessage());
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
            @RequestParam(required = false) String taskIp,
            @RequestParam(required = false) Integer scheduleStatus,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
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
                    subAccount, taskId, taskIp, scheduleStatus,
                    startTime, endTime, pageNum, pageSize);
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
     * 查询IP地理位置信息
     */
    @GetMapping("/api/monitor/queue/ip-location")
    public Map<String, Object> getIpLocation(
            @RequestParam String ip,
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
            IpLocationUtil.IpLocationInfo info = IpLocationUtil.getLocation(ip);
            result.put("code", 200);
            result.put("data", info);
        } catch (Exception e) {
            logger.error("查询IP地理位置失败: {}", ip, e);
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
            Map<String, Object> scanResult = logMonitorTask.manualScanQueueTimeoutTasks();

            result.put("code", 200);
            result.put("msg", "手动扫描完成");
            result.put("newAlerts", scanResult.get("newAlerts"));
            result.put("updatedAlerts", scanResult.get("updatedAlerts"));
            result.put("autoClosed", scanResult.get("autoClosed"));
            result.put("weChatAlerts", scanResult.get("weChatAlerts"));
            result.put("total", scanResult.get("total"));
        } catch (Exception e) {
            logger.error("手动扫描失败", e);
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
     * 批量修复队列任务DR
     */
    @PostMapping("/api/monitor/queue-monitor/batch-fix")
    public Map<String, Object> batchFixQueueTasks(
            @RequestBody Map<String, Object> params,
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
            List<String> taskIds = (List<String>) params.get("taskIds");
            if (taskIds == null || taskIds.isEmpty()) {
                result.put("code", 400);
                result.put("msg", "任务ID列表不能为空");
                return result;
            }

            // 1. 批量修复任务DR
            int updated = smsQueueMapper.batchFixTaskDr(taskIds);

            // 2. 更新任务主表统计
            smsQueueMapper.batchUpdateTaskStats(taskIds);

            // 3. 更新监控日志状态
            queueMonitorLogMapper.batchUpdateStatus(taskIds, loginUsername, LocalDateTime.now());

            result.put("code", 200);
            result.put("msg", String.format("批量修复成功，共更新 %d 条记录，涉及 %d 个任务", updated, taskIds.size()));
            result.put("updated", updated);
            result.put("taskCount", taskIds.size());
            logger.info("批量修复任务: operator={}, taskIds={}, updated={}", loginUsername, taskIds, updated);
        } catch (Exception e) {
            logger.error("批量修复失败", e);
            result.put("code", 500);
            result.put("msg", "批量修复失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 获取客户定时任务配置(DR自动修复)配置列表
     */
    @GetMapping("/api/monitor/dr-auto-fix/list")
    public Map<String, Object> getDrAutoFixList(
            @RequestParam(required = false) String subAccount,
            @RequestParam(required = false) Integer enabled,
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
            Map<String, Object> data = customerDrAutoFixService.getConfigList(subAccount, enabled, pageNum, pageSize);
            result.put("code", 200);
            result.put("data", data);
        } catch (Exception e) {
            logger.error("查询定时任务配置(DR自动修复)配置列表失败", e);
            result.put("code", 500);
            result.put("msg", "查询失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 保存客户定时任务配置(DR自动修复)配置
     */
    @PostMapping("/api/monitor/dr-auto-fix/save")
    public Map<String, Object> saveDrAutoFix(@RequestBody CustomerDrAutoFixConfig config,
                                             @RequestParam String secret,
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

        // 参数校验
        if (config.getSubAccount() == null || config.getSubAccount().trim().isEmpty()) {
            result.put("code", 400);
            result.put("msg", "客户账号不能为空");
            return result;
        }
        if (config.getTimeoutMinutes() == null || config.getTimeoutMinutes() < 1) {
            result.put("code", 400);
            result.put("msg", "超时时间必须大于0");
            return result;
        }

        try {
            customerDrAutoFixService.saveConfig(config, loginUsername);
            result.put("code", 200);
            result.put("msg", "保存成功");
        } catch (Exception e) {
            logger.error("保存定时任务配置(DR自动修复)配置失败", e);
            result.put("code", 500);
            result.put("msg", "保存失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 切换配置启用/禁用状态
     */
    @PostMapping("/api/monitor/dr-auto-fix/toggle")
    public Map<String, Object> toggleDrAutoFix(@RequestParam Long id,
                                               @RequestParam Integer enabled,
                                               @RequestParam String secret,
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
            CustomerDrAutoFixConfig config = customerDrAutoFixService.getConfigById(id);
            if (config == null) {
                result.put("code", 404);
                result.put("msg", "配置不存在");
                return result;
            }
            config.setEnabled(enabled);
            config.setUpdatedBy(loginUsername);
            customerDrAutoFixService.saveConfig(config, loginUsername);

            String status = enabled == 1 ? "启用" : "禁用";
            result.put("code", 200);
            result.put("msg", status + "成功");
        } catch (Exception e) {
            logger.error("切换定时任务配置(DR自动修复)配置状态失败", e);
            result.put("code", 500);
            result.put("msg", "操作失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 删除客户定时任务配置(DR自动修复)配置
     */
    @PostMapping("/api/monitor/dr-auto-fix/delete")
    public Map<String, Object> deleteDrAutoFix(@RequestParam Long id,
                                               @RequestParam String secret,
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
            customerDrAutoFixService.deleteConfig(id, loginUsername);
            result.put("code", 200);
            result.put("msg", "删除成功");
        } catch (Exception e) {
            logger.error("删除定时任务配置(DR自动修复)配置失败", e);
            result.put("code", 500);
            result.put("msg", "删除失败: " + e.getMessage());
        }
        return result;
    }

    /* ============ 客户端功能模块管理 ============ */

    @GetMapping("/monitor/feature-management")
    public String featureManagementPage(HttpServletRequest request) {
        HttpSession session = request.getSession();
        String clientIp = getRealIp(request);
        String loginUsername = getCurrentUsername(session);

        if (!isIpAllowed(clientIp, loginUsername)) {
            return "<h1 style='color:red;text-align:center;margin-top:100px;'>❌ 仅允许内网访问<br/>您的IP：" + clientIp + "</h1>";
        }
        return generateFeatureManagementHtml();
    }

    @GetMapping("/api/monitor/feature/modules")
    public Map<String, Object> listFeatureModules(@RequestParam String secret, HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        if (!MONITOR_SECRET.equals(secret)) { result.put("code", 401); result.put("msg", "无权限"); return result; }
        if (!isIpAllowed(getRealIp(request), getCurrentUsername(request.getSession()))) {
            result.put("code", 403); result.put("msg", "仅允许内网访问"); return result;
        }
        try {
            result.put("code", 200);
            result.put("data", featureService.listAllModules());
        } catch (Exception e) {
            logger.error("获取模块列表失败", e);
            result.put("code", 500); result.put("msg", "查询失败: " + e.getMessage());
        }
        return result;
    }

    @PostMapping("/api/monitor/feature/module/save")
    public Map<String, Object> saveFeatureModule(@RequestBody FeatureModule module,
                                                 @RequestParam String secret,
                                                 HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        if (!MONITOR_SECRET.equals(secret)) { result.put("code", 401); result.put("msg", "无权限"); return result; }
        if (!isIpAllowed(getRealIp(request), getCurrentUsername(request.getSession()))) {
            result.put("code", 403); result.put("msg", "仅允许内网访问"); return result;
        }
        try {
            featureService.saveModule(module);
            result.put("code", 200); result.put("msg", "保存成功");
        } catch (Exception e) {
            logger.error("保存模块失败", e);
            result.put("code", 500); result.put("msg", "保存失败: " + e.getMessage());
        }
        return result;
    }

    @PostMapping("/api/monitor/feature/module/toggle")
    public Map<String, Object> toggleFeatureModule(@RequestParam Long id,
                                                   @RequestParam Integer enabled,
                                                   @RequestParam String secret,
                                                   HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        if (!MONITOR_SECRET.equals(secret)) { result.put("code", 401); return result; }
        if (!isIpAllowed(getRealIp(request), getCurrentUsername(request.getSession()))) {
            result.put("code", 403); return result;
        }
        try {
            featureService.toggleModule(id, enabled);
            result.put("code", 200); result.put("msg", enabled == 1 ? "启用成功" : "禁用成功");
        } catch (Exception e) {
            logger.error("切换模块状态失败", e);
            result.put("code", 500); result.put("msg", e.getMessage());
        }
        return result;
    }

    @PostMapping("/api/monitor/feature/module/delete")
    public Map<String, Object> deleteFeatureModule(@RequestParam Long id,
                                                   @RequestParam String secret,
                                                   HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        if (!MONITOR_SECRET.equals(secret)) { result.put("code", 401); return result; }
        if (!isIpAllowed(getRealIp(request), getCurrentUsername(request.getSession()))) {
            result.put("code", 403); return result;
        }
        try {
            // 先删授权，再删模块（避免脏数据）
            java.util.List<CustomerFeatureAuth> authList = featureService.listAuthByModule(
                    featureService.listAllModules().stream()
                            .filter(m -> m.getId().equals(id))
                            .findFirst().map(FeatureModule::getModuleCode).orElse(null));
            if (authList != null) {
                for (CustomerFeatureAuth a : authList) {
                    featureService.removeAuth(a.getSubAccount(), a.getModuleCode());
                }
            }
            // 直接调用 mapper 删除
            featureService.deleteModule(id);
            result.put("code", 200); result.put("msg", "删除成功");
        } catch (Exception e) {
            logger.error("删除模块失败", e);
            result.put("code", 500); result.put("msg", e.getMessage());
        }
        return result;
    }

    @GetMapping("/api/monitor/feature/module/customers")
    public Map<String, Object> listModuleCustomers(@RequestParam String secret,
                                                   @RequestParam String moduleCode,
                                                   HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        if (!MONITOR_SECRET.equals(secret)) { result.put("code", 401); return result; }
        if (!isIpAllowed(getRealIp(request), getCurrentUsername(request.getSession()))) {
            result.put("code", 403); return result;
        }
        try {
            result.put("code", 200);
            result.put("data", featureService.listAuthByModule(moduleCode));
        } catch (Exception e) {
            result.put("code", 500); result.put("msg", e.getMessage());
        }
        return result;
    }

    @PostMapping("/api/monitor/feature/auth/save")
    public Map<String, Object> saveFeatureAuth(@RequestParam String subAccount,
                                               @RequestParam String moduleCode,
                                               @RequestParam Integer enabled,
                                               @RequestParam String secret,
                                               HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        if (!MONITOR_SECRET.equals(secret)) { result.put("code", 401); return result; }
        if (!isIpAllowed(getRealIp(request), getCurrentUsername(request.getSession()))) {
            result.put("code", 403); return result;
        }
        try {
            featureService.saveAuth(subAccount, moduleCode, enabled);
            result.put("code", 200); result.put("msg", "操作成功");
        } catch (Exception e) {
            logger.error("授权失败", e);
            result.put("code", 500); result.put("msg", e.getMessage());
        }
        return result;
    }

    @PostMapping("/api/monitor/feature/auth/delete")
    public Map<String, Object> deleteFeatureAuth(@RequestParam String subAccount,
                                                 @RequestParam String moduleCode,
                                                 @RequestParam String secret,
                                                 HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        if (!MONITOR_SECRET.equals(secret)) { result.put("code", 401); return result; }
        if (!isIpAllowed(getRealIp(request), getCurrentUsername(request.getSession()))) {
            result.put("code", 403); return result;
        }
        try {
            featureService.removeAuth(subAccount, moduleCode);
            result.put("code", 200); result.put("msg", "删除成功");
        } catch (Exception e) {
            logger.error("删除授权失败", e);
            result.put("code", 500); result.put("msg", e.getMessage());
        }
        return result;
    }

    /**
     * 管理端：获取所有子任务列表
     */
    @GetMapping("/api/monitor/subtask/list")
    public Map<String, Object> listSubTasks(
            @RequestParam(required = false) String subAccount,
            @RequestParam(required = false) String taskId,
            @RequestParam(defaultValue = "100") int limit,
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
            // subAccount 如果传空，底层存储过程会查全部
            List<Map<String, String>> list = subTaskService.queryAllSubTasks(subAccount, taskId, limit);
            result.put("code", 200);
            result.put("data", list);
        } catch (Exception e) {
            logger.error("查询子任务列表失败", e);
            result.put("code", 500);
            result.put("msg", "查询失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 管理端：操作子任务（暂停/恢复/取消）
     */
    @PostMapping("/api/monitor/subtask/operate")
    public Map<String, Object> operateSubTask(
            @RequestParam String action,
            @RequestParam String taskId,
            @RequestParam(defaultValue = "0") int subIndex,
            @RequestParam String secret,
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
            // 管理端操作，备注带上操作人信息
            String remark = "管理端(" + loginUsername + ")操作";
            int affected = subTaskService.operateSubTask(action, taskId, subIndex, remark);

            result.put("code", 200);
            result.put("msg", "操作成功，影响了 " + affected + " 个子任务");
        } catch (Exception e) {
            logger.error("操作子任务失败", e);
            result.put("code", 500);
            result.put("msg", "操作失败: " + e.getMessage());
        }
        return result;
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
                "<html lang='zh-CN'>\n" +
                "<head>\n" +
                "<meta charset='UTF-8'>\n" +
                "<meta name='viewport' content='width=device-width, initial-scale=1.0, user-scalable=no'>\n" +
                "<title>Texcell LTM WEB 监控系统登录</title>\n" +
                "<link rel='icon' type='image/svg+xml' href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='22' fill='%231677ff'/%3E%3Ctext x='50' y='72' font-size='60' font-weight='bold' text-anchor='middle' fill='white' font-family='Arial'%3ET%3C/text%3E%3C/svg%3E\">\n" +
                "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap' rel='stylesheet'>\n" +
                "<style>\n" +
                ":root{--primary-color:#1677ff;--primary-hover:#3c8dff;--text-primary:#1e293b;--text-secondary:#475569;--text-muted:#8a97a8;--input-border:#d9e2ec;}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{\n" +
                "  font-family:'Inter','PingFang SC','Microsoft YaHei','Helvetica Neue',Arial,sans-serif;\n" +
                "  min-height:100vh;display:flex;justify-content:center;align-items:center;\n" +
                "  position:relative;overflow:hidden;\n" +
                "  background: #0f2040 url('/images/texcell_login_bg.png') no-repeat center / cover fixed;\n" +
                "}\n" +
                "@media (max-width:768px){\n" +
                "  body{\n" +
                "    background-attachment:scroll;\n" +
                "  }\n" +
                "}\n" +
                "body::before{\n" +
                "  content:'';\n" +
                "  position:fixed;inset:0;\n" +
                "  background:linear-gradient(115deg,rgba(255,255,255,0.25) 0%,rgba(255,255,255,0.10) 50%,rgba(0,20,60,0.35) 100%);\n" +
                "  backdrop-filter:blur(0.2px);\n" +
                "  -webkit-backdrop-filter:blur(0.2px);\n" +
                "  pointer-events:none;\n" +
                "}\n" +
                ".login-container{position:relative;z-index:10;width:100%;max-width:440px;margin:20px;animation:fadeInUp .6s ease-out;}\n" +
                "@media(min-width:1200px){body{justify-content:flex-end;padding-right:9vw;}}\n" +
                "@keyframes fadeInUp{from{opacity:0;transform:translateY(24px);}to{opacity:1;transform:translateY(0);}}\n" +
                ".login-card{background:rgba(255,255,255,0.96);-webkit-backdrop-filter:blur(12px);backdrop-filter:blur(12px);border:1px solid rgba(255,255,255,0.7);border-radius:16px;padding:44px 40px;box-shadow:0 24px 60px -12px rgba(15,45,105,0.25);}\n" +
                ".logo-area{text-align:center;margin-bottom:30px;}\n" +
                ".logo-icon{width:60px;height:60px;background:linear-gradient(135deg,#1677ff 0%,#3f8cff 100%);border-radius:16px;display:flex;align-items:center;justify-content:center;margin:0 auto 14px;box-shadow:0 10px 22px -6px rgba(22,119,255,0.45);}\n" +
                ".login-card h2{font-size:26px;font-weight:700;text-align:center;color:var(--text-primary);margin-bottom:6px;letter-spacing:0.5px;}\n" +
                ".login-sub{text-align:center;color:var(--text-muted);font-size:13px;margin-bottom:30px;letter-spacing:0.3px;}\n" +
                ".input-group{margin-bottom:18px;}\n" +
                ".input-label{display:block;font-size:13px;font-weight:500;color:var(--text-secondary);margin-bottom:7px;}\n" +
                ".input-wrapper{position:relative;}\n" +
                ".input-icon{position:absolute;left:14px;top:50%;transform:translateY(-50%);color:#9aa7b8;display:flex;pointer-events:none;}\n" +
                ".input-wrapper input{width:100%;height:48px;padding:0 44px 0 42px;border:1px solid var(--input-border);border-radius:10px;font-size:14px;color:var(--text-primary);background:#fff;transition:border-color .2s,box-shadow .2s;}\n" +
                ".input-wrapper input::placeholder{color:#a8b3c4;}\n" +
                ".input-wrapper input:focus{outline:none;border-color:var(--primary-color);box-shadow:0 0 0 3px rgba(22,119,255,0.12);}\n" +
                ".toggle-pwd{position:absolute;right:10px;top:50%;transform:translateY(-50%);cursor:pointer;color:#9aa7b8;background:none;border:none;display:flex;padding:4px;}\n" +
                ".toggle-pwd:hover{color:var(--primary-color);}\n" +
                ".login-btn{width:100%;height:48px;background:var(--primary-color);color:#fff;border:none;border-radius:10px;font-size:15px;font-weight:600;letter-spacing:6px;text-indent:6px;cursor:pointer;transition:background .2s,box-shadow .2s;margin-top:10px;display:flex;align-items:center;justify-content:center;}\n" +
                ".login-btn:hover{background:var(--primary-hover);box-shadow:0 8px 20px -6px rgba(22,119,255,0.45);}\n" +
                ".login-btn:active{background:#0f66e8;}\n" +
                ".login-btn.loading{opacity:0.7;cursor:not-allowed;}\n" +
                ".login-btn .spinner{width:18px;height:18px;border:2px solid rgba(255,255,255,0.35);border-top-color:#fff;border-radius:50%;animation:spin .8s linear infinite;display:none;}\n" +
                ".login-btn.loading .spinner{display:inline-block;}\n" +
                ".login-btn.loading .btn-text{display:none;}\n" +
                "@keyframes spin{to{transform:rotate(360deg);}}\n" +
                ".error-msg{background:#fef2f2;border:1px solid #fecaca;border-radius:10px;padding:11px 14px;margin-top:18px;display:none;align-items:center;gap:10px;}\n" +
                ".error-msg.show{display:flex;}\n" +
                ".error-msg .error-icon{color:#dc2626;display:flex;flex-shrink:0;}\n" +
                ".error-msg .error-text{color:#dc2626;font-size:13px;flex:1;}\n" +
                ".footer{text-align:center;margin-top:22px;color:var(--text-muted);font-size:12px;letter-spacing:0.3px;}\n" +
                "@media(max-width:600px){.login-card{padding:34px 22px;}.login-card h2{font-size:22px;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class='login-container'>\n" +
                "<div class='login-card'>\n" +
                "<div class='logo-area'>\n" +
                "<div class='logo-icon'><svg width='34' height='34' viewBox='0 0 100 100' fill='none'><path d='M50 8 L86 29 V71 L50 92 L14 71 V29 Z' stroke='white' stroke-width='7' stroke-linejoin='round'/><text x='50' y='67' font-size='42' font-weight='bold' text-anchor='middle' fill='white' font-family='Arial'>T</text></svg></div>\n" +
                "<h2>Texcell LTM 监控系统</h2>\n" +
                "<div class='login-sub'>Texcell SMS 服务监控平台</div>\n" +
                "</div>\n" +
                "<div class='input-group'>\n" +
                "<label class='input-label'>账号</label>\n" +
                "<div class='input-wrapper'>\n" +
                "<span class='input-icon'><svg width='18' height='18' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><path d='M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2'/><circle cx='12' cy='7' r='4'/></svg></span>\n" +
                "<input type='text' id='username' placeholder='请输入账号' autocomplete='off' readonly onfocus=\"this.removeAttribute('readonly')\">\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class='input-group'>\n" +
                "<label class='input-label'>密码</label>\n" +
                "<div class='input-wrapper'>\n" +
                "<span class='input-icon'><svg width='18' height='18' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><rect x='3' y='11' width='18' height='11' rx='2'/><path d='M7 11V7a5 5 0 0 1 10 0v4'/></svg></span>\n" +
                "<input type='password' id='password' placeholder='请输入密码' autocomplete='off' readonly onfocus=\"this.removeAttribute('readonly')\">\n" +
                "<button type='button' class='toggle-pwd' onclick='togglePassword()'><svg class='eye-open' width='18' height='18' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><path d='M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7z'/><circle cx='12' cy='12' r='3'/></svg><svg class='eye-close' width='18' height='18' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' style='display:none'><path d='M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24'/><line x1='1' y1='1' x2='23' y2='23'/></svg></button>\n" +
                "</div>\n" +
                "</div>\n" +
                "<button class='login-btn' id='loginBtn' onclick='login()'>\n" +
                "<span class='spinner'></span>\n" +
                "<span class='btn-text'>登录系统</span>\n" +
                "</button>\n" +
                "<div class='error-msg' id='errorMsg'>\n" +
                "<span class='error-icon'><svg width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round'><circle cx='12' cy='12' r='10'/><line x1='12' y1='8' x2='12' y2='12'/><line x1='12' y1='16' x2='12.01' y2='16'/></svg></span>\n" +
                "<span class='error-text' id='errorText'></span>\n" +
                "</div>\n" +
                "<div class='footer'>仅限内部人员访问 · 请妥善保管账号密码</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<script>\n" +
                "function togglePassword(){var pwd=document.getElementById('password');var open=document.querySelector('.eye-open');var close=document.querySelector('.eye-close');if(pwd.type==='password'){pwd.type='text';open.style.display='none';close.style.display='block';}else{pwd.type='password';open.style.display='block';close.style.display='none';}}\n" +
                "function showError(msg){var errDiv=document.getElementById('errorMsg');var errText=document.getElementById('errorText');errText.innerText=msg;errDiv.classList.add('show');setTimeout(function(){errDiv.classList.remove('show');},3000);}\n" +
                "function login(){var u=document.getElementById('username');var p=document.getElementById('password');var username=u.value.trim();var password=p.value;if(!username){showError('请输入账号');u.focus();return;}if(!password){showError('请输入密码');p.focus();return;}var btn=document.getElementById('loginBtn');btn.classList.add('loading');fetch('/monitor/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'username='+encodeURIComponent(username)+'&password='+encodeURIComponent(password)}).then(function(res){return res.json();}).then(function(data){if(data.code===200){window.location.href='/monitor/index';}else{showError(data.msg||'账号或密码错误');btn.classList.remove('loading');}}).catch(function(){showError('登录失败，请重试');btn.classList.remove('loading');});}\n" +
                "document.getElementById('password').addEventListener('keypress',function(e){if(e.key==='Enter')login();});\n" +
                "document.getElementById('username').addEventListener('keypress',function(e){if(e.key==='Enter')login();});\n" +
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
                "<title>Texcell LTM WEB 监控首页</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='22' fill='%231677ff'/%3E%3Ctext x='50' y='72' font-size='60' font-weight='bold' text-anchor='middle' fill='white' font-family='Arial'%3ET%3C/text%3E%3C/svg%3E\">\n" +
                "<script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:ital,wght@0,300;0,400;0,500;0,600;0,700;1,400&display=swap\" rel=\"stylesheet\">\n" +
                "<style>\n" +
                ":root {\n" +
                "  --primary-color:#1677ff;\n" +
                "  --primary-hover:#3c8dff;\n" +
                "  --text-primary:#1e293b;\n" +
                "  --text-secondary:#475569;\n" +
                "  --text-muted:#8a97a8;\n" +
                "  --input-border:#d9e2ec;\n" +
                "  --bg-page:#f5f7fa;\n" +
                "  --bg-card:#ffffff;\n" +
                "  --border-color:#e8ecf1;\n" +
                "  --shadow-sm:0 1px 3px rgba(0,0,0,0.06);\n" +
                "  --shadow-hover:0 4px 12px rgba(22,119,255,0.1);\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:var(--bg-page);font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;}\n" +
                ".navbar{background:var(--bg-card);box-shadow:var(--shadow-sm);border-bottom:1px solid var(--border-color);position:sticky;top:0;z-index:100;}\n" +
                ".nav-container{max-width:2100px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;padding:0 32px;height:70px;}\n" +
                ".logo{display:flex;align-items:center;gap:12px;}\n" +
                ".logo-icon{width:40px;height:40px;background:linear-gradient(135deg,var(--primary-color) 0%,var(--primary-hover) 100%);border-radius:12px;display:flex;align-items:center;justify-content:center;}\n" +
                ".logo-icon svg{width:22px;height:22px;stroke:#fff;stroke-width:2;fill:none;}\n" +
                ".logo-text{font-size:18px;font-weight:700;color:var(--text-primary);letter-spacing:1px;}\n" +
                ".logo-sub{font-size:11px;color:var(--text-muted);margin-left:8px;}\n" +
                ".menu{display:flex;gap:12px;}\n" +
                ".menu a{color:var(--text-secondary);text-decoration:none;padding:8px 20px;border-radius:40px;transition:all 0.3s;font-weight:500;font-size:14px;}\n" +
                ".menu a:hover{background:#f1f5f9;color:var(--text-primary);}\n" +
                ".menu a.active{background:#e8f0fe;color:var(--primary-color);box-shadow:none;}\n" +
                ".user-info{display:flex;align-items:center;gap:20px;}\n" +
                ".user-name{color:var(--text-secondary);font-size:14px;}\n" +
                ".user-role{background:rgba(22,119,255,0.1);padding:4px 12px;border-radius:20px;font-size:12px;color:var(--primary-color);}\n" +
                ".logout-btn{background:#f1f5f9;border:1px solid var(--border-color);color:var(--text-secondary);padding:8px 20px;border-radius:40px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".page-wrap{max-width:2100px;margin:0 auto;padding:32px;}\n" +
                ".welcome-card{background:var(--bg-card);border-radius:16px;padding:36px 40px;margin-bottom:32px;color:var(--text-primary);position:relative;overflow:hidden;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);}\n" +
                ".welcome-card h1{font-size:28px;font-weight:700;margin-bottom:8px;}\n" +
                ".welcome-card p{font-size:14px;color:var(--text-secondary);margin-bottom:16px;}\n" +
                ".welcome-stats{display:flex;gap:32px;margin-top:16px;}\n" +
                ".welcome-stat{display:flex;align-items:center;gap:8px;font-size:13px;color:var(--text-secondary);}\n" +
                ".stats-grid{display:grid;grid-template-columns:repeat(5,1fr);gap:20px;margin-bottom:32px;}\n" +
                ".stat-card{background:var(--bg-card);border-radius:12px;padding:24px 20px;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);transition:all 0.3s;}\n" +
                ".stat-card:hover{transform:translateY(-2px);box-shadow:var(--shadow-hover);border-color:var(--primary-color);}\n" +
                ".stat-icon{margin-bottom:12px;}\n" +
                ".stat-icon svg{width:28px;height:28px;stroke:var(--primary-color);stroke-width:2;fill:none;}\n" +
                ".stat-number{font-size:36px;font-weight:800;color:var(--text-primary);line-height:1;}\n" +
                ".stat-label{font-size:13px;color:var(--text-secondary);margin-top:8px;font-weight:500;}\n" +
                ".stat-trend{font-size:12px;margin-top:8px;padding-top:8px;border-top:1px solid var(--border-color);color:var(--text-secondary);}\n" +
                ".section-title{font-size:18px;font-weight:700;color:var(--text-primary);margin-bottom:20px;display:flex;align-items:center;gap:8px;}\n" +
                ".section-title::before{content:'';width:4px;height:20px;background:linear-gradient(135deg,var(--primary-color) 0%,var(--primary-hover) 100%);border-radius:4px;}\n" +
                ".chart-row{display:grid;grid-template-columns:1fr 1fr;gap:24px;margin-bottom:32px;}\n" +
                ".chart-card{background:var(--bg-card);border-radius:12px;padding:24px;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);transition:all 0.3s;}\n" +
                ".chart-card:hover{box-shadow:var(--shadow-hover);}\n" +
                ".chart-card h3{font-size:16px;font-weight:600;color:var(--text-primary);margin-bottom:20px;display:flex;align-items:center;gap:8px;}\n" +
                ".chart-card h3 svg{width:18px;height:18px;stroke:var(--primary-color);stroke-width:2;fill:none;}\n" +
                ".user-list-card{background:var(--bg-card);border-radius:12px;padding:24px;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);margin-bottom:32px;}\n" +
                ".user-list-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:20px;flex-wrap:wrap;gap:12px;}\n" +
                ".user-list-header h3{font-size:16px;font-weight:600;color:var(--text-primary);display:flex;align-items:center;gap:8px;}\n" +
                ".user-list-header h3 svg{width:18px;height:18px;stroke:var(--primary-color);stroke-width:2;fill:none;}\n" +
                ".user-badge{background:linear-gradient(135deg,var(--primary-color) 0%,var(--primary-hover) 100%);color:white;border-radius:20px;padding:4px 12px;font-size:12px;font-weight:500;}\n" +
                ".user-items{display:flex;flex-wrap:wrap;gap:12px;}\n" +
                ".user-item{background:#f8fafc;border:1px solid var(--border-color);border-radius:40px;padding:8px 20px;font-size:14px;color:var(--text-primary);transition:all 0.3s;display:inline-flex;align-items:center;gap:8px;}\n" +
                ".user-item:hover{transform:translateY(-2px);border-color:var(--primary-color);box-shadow:0 2px 8px rgba(22,119,255,0.1);}\n" +
                ".user-item .user-avatar{width:24px;height:24px;background:linear-gradient(135deg,var(--primary-color) 0%,var(--primary-hover) 100%);border-radius:50%;display:inline-flex;align-items:center;justify-content:center;font-size:12px;color:white;}\n" +
                ".empty-users{color:var(--text-muted);text-align:center;padding:32px;font-size:14px;}\n" +
                ".footer{text-align:center;padding:32px 0;color:var(--text-muted);font-size:12px;border-top:1px solid var(--border-color);margin-top:16px;}\n" +
                "@media(max-width:1024px){.stats-grid{grid-template-columns:repeat(3,1fr);}}\n" +
                "@media(max-width:768px){.nav-container{padding:0 20px;}.menu a{padding:6px 14px;font-size:12px;}.stats-grid{grid-template-columns:repeat(2,1fr);}.chart-row{grid-template-columns:1fr;}.page-wrap{padding:20px;}}\n" +
                "@media(max-width:480px){.stats-grid{grid-template-columns:1fr;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"navbar\">\n" +
                "<div class=\"nav-container\">\n" +
                "<div class=\"logo\">\n" +
                "<div class=\"logo-icon\">\n" +
                "<svg viewBox=\"0 0 24 24\"><path d=\"M3 3v18h18\"/><path d=\"M18.7 8l-5.1 5.2-2.8-2.7L7 14.3\"/></svg>\n" +
                "</div>\n" +
                "<div class=\"logo-text\">Texcell LTM 监控管理系统</div>\n" +
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
                "<a href=\"/monitor/dr-auto-fix\">定时任务配置</a>\n" +
                "<a href=\"/monitor/feature-management\">功能模块管理</a>\n" +
                "<a href=\"/monitor/subtask-management\">子任务队列</a>\n" +
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
                "<div class=\"section-title\">今日活跃账号</div>\n" +
                "<div class=\"user-list-card\" id=\"userListCard\">\n" +
                "<div class=\"user-list-header\">\n" +
                "<h3><svg viewBox=\"0 0 24 24\"><path d=\"M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2\"/><circle cx=\"9\" cy=\"7\" r=\"4\"/><path d=\"M23 21v-2a4 4 0 0 0-3-3.87\"/><path d=\"M16 3.13a4 4 0 0 1 0 7.75\"/></svg> 今日登录账号列表</h3>\n" +
                "<span class=\"user-badge\" id=\"userCountBadge\">加载中...</span>\n" +
                "</div>\n" +
                "<div class=\"user-items\" id=\"userItems\">\n" +
                "<div class=\"empty-users\">加载中...</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"section-title\">数据分析</div>\n" +
                "<div class=\"chart-row\">\n" +
                "<div class=\"chart-card\"><h3><svg viewBox=\"0 0 24 24\"><path d=\"M3 3v18h18\"/><path d=\"M18.7 8l-5.1 5.2-2.8-2.7L7 14.3\"/></svg> 今日登录趋势（按小时）</h3><canvas id=\"hourlyChart\" width=\"400\" height=\"200\"></canvas></div>\n" +
                "<div class=\"chart-card\"><h3><svg viewBox=\"0 0 24 24\"><polygon points=\"12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2\"/></svg> 失败IP Top 5</h3><canvas id=\"failIpChart\" width=\"400\" height=\"200\"></canvas></div>\n" +
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
                "                var roleText = data.data.role === 'ADMIN' ? '管理员' : 'NOC';\n" +
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
                "fetch('/api/monitor/login-stats?secret=dn17ORUwf36Znl').then(function(res){return res.json();}).then(function(data){if(data.code===200&&data.data){var t=data.data.today||{},w=data.data.week||{},m=data.data.month||{},two=data.data.twoFactorStats||[];var google=0,email=0;for(var i=0;i<two.length;i++){var s=two[i];if(s.twoFactorType==='GOOGLE')google=s.totalCount||0;if(s.twoFactorType==='EMAIL')email=s.totalCount||0;}var successRate=t.total>0?(t.success*100/t.total).toFixed(1):0;document.getElementById('statsGrid').innerHTML='<div class=\"stat-card\"><div class=\"stat-icon\"><svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"4\" width=\"18\" height=\"18\" rx=\"2\" ry=\"2\"></rect><line x1=\"16\" y1=\"2\" x2=\"16\" y2=\"6\"></line><line x1=\"8\" y1=\"2\" x2=\"8\" y2=\"6\"></line><line x1=\"3\" y1=\"10\" x2=\"21\" y2=\"10\"></line></svg></div><div class=\"stat-number\">'+(t.total||0)+'</div><div class=\"stat-label\">今日登录</div><div class=\"stat-trend\">成功 '+(t.success||0)+' / 失败 '+(t.fail||0)+'</div></div><div class=\"stat-card\"><div class=\"stat-icon\"><svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"4\" width=\"18\" height=\"18\" rx=\"2\" ry=\"2\"></rect><line x1=\"16\" y1=\"2\" x2=\"16\" y2=\"6\"></line><line x1=\"8\" y1=\"2\" x2=\"8\" y2=\"6\"></line><line x1=\"3\" y1=\"10\" x2=\"21\" y2=\"10\"></line></svg></div><div class=\"stat-number\">'+(w.total||0)+'</div><div class=\"stat-label\">本周登录</div><div class=\"stat-trend\">较上周 '+(w.total>m.total?'↑':'↓')+Math.abs(w.total-m.total)+'</div></div><div class=\"stat-card\"><div class=\"stat-icon\"><svg viewBox=\"0 0 24 24\"><path d=\"M3 3v18h18\"/><path d=\"M18.7 8l-5.1 5.2-2.8-2.7L7 14.3\"/></svg></div><div class=\"stat-number\">'+(m.total||0)+'</div><div class=\"stat-label\">本月登录</div></div><div class=\"stat-card\"><div class=\"stat-icon\"><svg viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"10\"></circle><path d=\"M12 16v-4M12 8h.01\"></path></svg></div><div class=\"stat-number\">'+successRate+'%</div><div class=\"stat-label\">今日成功率</div></div><div class=\"stat-card\"><div class=\"stat-icon\"><svg viewBox=\"0 0 24 24\"><path d=\"M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2\"/><circle cx=\"9\" cy=\"7\" r=\"4\"/><path d=\"M23 21v-2a4 4 0 0 0-3-3.87\"/><path d=\"M16 3.13a4 4 0 0 1 0 7.75\"/></svg></div><div class=\"stat-number\">'+(data.data.todayActiveUsers||0)+'</div><div class=\"stat-label\">今日活跃账号</div></div>';document.getElementById('welcomeStats').innerHTML='<div class=\"welcome-stat\">服务运行: '+(data.runDuration||'计算中')+'</div>';\n" +
                "  renderActiveUsers(data.data.todayActiveUserList || []);\n" +
                "  if(data.data.hourlyTrend&&data.data.hourlyTrend.length>0){var hours=[],totals=[];for(var j=0;j<data.data.hourlyTrend.length;j++){var item=data.data.hourlyTrend[j];hours.push(item.hour+':00');totals.push(item.totalCount||0);}if(hourlyChart)hourlyChart.destroy();var ctx=document.getElementById('hourlyChart').getContext('2d');hourlyChart=new Chart(ctx,{type:'line',data:{labels:hours,datasets:[{label:'登录次数',data:totals,borderColor:'#1677ff',backgroundColor:'rgba(22,119,255,0.08)',fill:true,tension:0.3,borderWidth:2,pointRadius:3,pointBackgroundColor:'#1677ff'}]},options:{responsive:true,maintainAspectRatio:true,plugins:{legend:{position:'top'}}}});}if(data.data.todayTopFailIps&&data.data.todayTopFailIps.length>0){var ips=[],counts=[];for(var k=0;k<data.data.todayTopFailIps.length;k++){var item=data.data.todayTopFailIps[k];ips.push(item.realIp);counts.push(item.failCount||0);}if(failIpChart)failIpChart.destroy();var ctx2=document.getElementById('failIpChart').getContext('2d');failIpChart=new Chart(ctx2,{type:'bar',data:{labels:ips,datasets:[{label:'失败次数',data:counts,backgroundColor:'#e53935',borderRadius:8,barPercentage:0.7}]},options:{responsive:true,maintainAspectRatio:true,plugins:{legend:{position:'top'}}}});}}}).catch(function(err){console.error(err);});\n" +
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
                "<title>Texcell LTM WEB 服务监控</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='22' fill='%231677ff'/%3E%3Ctext x='50' y='72' font-size='60' font-weight='bold' text-anchor='middle' fill='white' font-family='Arial'%3ET%3C/text%3E%3C/svg%3E\">\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:ital,wght@0,300;0,400;0,500;0,600;0,700;1,400&display=swap\" rel=\"stylesheet\">\n" +
                "<style>\n" +
                ":root {\n" +
                "  --primary-color:#1677ff;\n" +
                "  --primary-hover:#3c8dff;\n" +
                "  --text-primary:#1e293b;\n" +
                "  --text-secondary:#475569;\n" +
                "  --text-muted:#8a97a8;\n" +
                "  --input-border:#d9e2ec;\n" +
                "  --bg-page:#f5f7fa;\n" +
                "  --bg-card:#ffffff;\n" +
                "  --border-color:#e8ecf1;\n" +
                "  --shadow-sm:0 1px 3px rgba(0,0,0,0.06);\n" +
                "  --shadow-hover:0 4px 12px rgba(22,119,255,0.1);\n" +
                "  --success-color:#52c41a;\n" +
                "  --danger-color:#ff4d4f;\n" +
                "  --warn-color:#faad14;\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:var(--bg-page);font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;}\n" +
                ".navbar{background:var(--bg-card);box-shadow:var(--shadow-sm);border-bottom:1px solid var(--border-color);position:sticky;top:0;z-index:100;}\n" +
                ".nav-container{max-width:2100px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;padding:0 32px;height:70px;}\n" +
                ".logo{display:flex;align-items:center;gap:12px;}\n" +
                ".logo-icon{width:40px;height:40px;background:linear-gradient(135deg,var(--primary-color) 0%,var(--primary-hover) 100%);border-radius:12px;display:flex;align-items:center;justify-content:center;}\n" +
                ".logo-icon svg{width:22px;height:22px;stroke:#fff;stroke-width:2;fill:none;}\n" +
                ".logo-text{font-size:18px;font-weight:700;color:var(--text-primary);letter-spacing:1px;}\n" +
                ".logo-sub{font-size:11px;color:var(--text-muted);margin-left:8px;}\n" +
                ".menu{display:flex;gap:12px;}\n" +
                ".menu a{color:var(--text-secondary);text-decoration:none;padding:8px 20px;border-radius:40px;transition:all 0.3s;font-weight:500;font-size:14px;}\n" +
                ".menu a:hover{background:#f1f5f9;color:var(--text-primary);}\n" +
                ".menu a.active{background:#e8f0fe;color:var(--primary-color);box-shadow:none;}\n" +
                ".user-info{display:flex;align-items:center;gap:20px;}\n" +
                ".user-name{color:var(--text-secondary);font-size:14px;}\n" +
                ".user-role{background:rgba(22,119,255,0.1);padding:4px 12px;border-radius:20px;font-size:12px;color:var(--primary-color);}\n" +
                ".logout-btn{background:#f1f5f9;border:1px solid var(--border-color);color:var(--text-secondary);padding:8px 20px;border-radius:40px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".page-wrap{max-width:2100px;margin:0 auto;padding:32px;}\n" +
                ".status-header{background:var(--bg-card);border-radius:16px;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);padding:24px 32px;margin-bottom:32px;display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:16px;}\n" +
                ".status-info{display:flex;gap:32px;align-items:center;flex-wrap:wrap;}\n" +
                ".status-indicator{display:flex;align-items:center;gap:10px;}\n" +
                ".status-led{width:14px;height:14px;border-radius:50%;background-color:var(--success-color);box-shadow:0 0 12px rgba(82,196,26,0.3);animation:pulse 2s infinite;}\n" +
                "@keyframes pulse{0%,100%{opacity:1;}50%{opacity:0.5;}}\n" +
                ".status-text{font-size:15px;font-weight:600;color:var(--text-primary);}\n" +
                ".time-info{display:flex;gap:28px;font-size:13px;color:var(--text-secondary);flex-wrap:wrap;}\n" +
                ".time-info span{display:inline-flex;align-items:center;gap:6px;}\n" +
                ".time-info svg{width:15px;height:15px;stroke:var(--text-muted);stroke-width:2;fill:none;}\n" +
                ".refresh-btn{background:#f1f5f9;border:1px solid var(--border-color);color:var(--text-secondary);padding:8px 20px;border-radius:40px;cursor:pointer;font-size:13px;font-weight:500;transition:all 0.3s;display:inline-flex;align-items:center;gap:8px;}\n" +
                ".refresh-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".refresh-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".metrics-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:24px;margin-bottom:32px;}\n" +
                ".metric-card{background:var(--bg-card);border-radius:12px;padding:24px;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);transition:all 0.3s;}\n" +
                ".metric-card:hover{transform:translateY(-2px);box-shadow:var(--shadow-hover);border-color:var(--primary-color);}\n" +
                ".metric-title{font-size:14px;color:var(--text-muted);margin-bottom:12px;display:flex;align-items:center;gap:8px;font-weight:500;}\n" +
                ".metric-title svg{width:18px;height:18px;stroke:var(--primary-color);stroke-width:2;fill:none;}\n" +
                ".metric-value{font-size:34px;font-weight:800;color:var(--text-primary);line-height:1;}\n" +
                ".metric-unit{font-size:14px;font-weight:400;color:var(--text-muted);margin-left:4px;}\n" +
                ".metric-sub{font-size:12px;color:var(--text-muted);margin-top:12px;padding-top:10px;border-top:1px solid var(--border-color);}\n" +
                ".module-section{margin-bottom:40px;}\n" +
                ".section-title{font-size:18px;font-weight:700;color:var(--text-primary);margin-bottom:20px;display:flex;align-items:center;gap:8px;}\n" +
                ".section-title::before{content:'';width:4px;height:20px;background:linear-gradient(135deg,var(--primary-color) 0%,var(--primary-hover) 100%);border-radius:4px;}\n" +
                ".log-card{background:var(--bg-card);border-radius:12px;margin-bottom:32px;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);overflow:hidden;}\n" +
                ".log-card:last-child{margin-bottom:0;}\n" +
                ".log-header{padding:18px 24px;border-bottom:1px solid var(--border-color);background:#f8fafc;font-weight:600;font-size:16px;color:var(--text-primary);display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:12px;}\n" +
                ".log-header>span:first-child{display:inline-flex;align-items:center;gap:8px;}\n" +
                ".log-header>span:first-child svg{width:18px;height:18px;stroke:var(--primary-color);stroke-width:2;fill:none;}\n" +
                ".log-badge{background:linear-gradient(135deg,var(--primary-color) 0%,var(--primary-hover) 100%);color:white;border-radius:20px;padding:4px 12px;font-size:11px;font-weight:500;}\n" +
                ".log-badge-attack{background:var(--danger-color);color:white;border-radius:20px;padding:4px 12px;font-size:11px;font-weight:500;}\n" +
                ".table-wrapper{overflow-x:auto;}\n" +
                ".log-table{width:100%;border-collapse:collapse;}\n" +
                ".log-table th{background:#f8fafc;padding:14px 16px;text-align:left;font-size:13px;font-weight:600;color:var(--text-secondary);border-bottom:1px solid var(--border-color);white-space:nowrap;}\n" +
                ".log-table td{padding:14px 16px;font-size:13px;color:var(--text-secondary);border-bottom:1px solid var(--border-color);}\n" +
                ".log-table tr:hover td{background:#f8fafc;}\n" +
                ".success{color:var(--success-color);font-weight:600;}.fail{color:var(--danger-color);font-weight:600;}\n" +
                ".gc-warn{color:var(--warn-color);}.gc-error{color:var(--danger-color);}\n" +
                ".attack-critical{color:var(--danger-color);font-weight:600;}\n" +
                ".empty{text-align:center;padding:48px;color:var(--text-muted);}\n" +
                ".pagination{display:flex;justify-content:center;align-items:center;gap:12px;padding:16px 20px;border-top:1px solid var(--border-color);background:#f8fafc;flex-wrap:wrap;}\n" +
                ".page-btn{padding:8px 16px;border:1px solid var(--border-color);background:var(--bg-card);color:var(--text-primary);border-radius:40px;cursor:pointer;font-size:13px;transition:all 0.3s;font-weight:500;}\n" +
                ".page-btn:hover:not(:disabled){border-color:var(--primary-color);color:var(--primary-color);transform:translateY(-1px);}\n" +
                ".page-btn:disabled{opacity:0.5;cursor:not-allowed;background:#f1f5f9;}\n" +
                ".page-info{font-size:13px;color:var(--text-muted);}\n" +
                ".footer{text-align:center;padding:32px 0;color:var(--text-muted);font-size:12px;border-top:1px solid var(--border-color);margin-top:40px;}\n" +
                "@media(max-width:1024px){.metrics-grid{grid-template-columns:repeat(3,1fr);}}\n" +
                "@media(max-width:768px){.nav-container{padding:0 20px;}.menu a{padding:6px 14px;font-size:12px;}.metrics-grid{grid-template-columns:repeat(2,1fr);}.status-info{flex-direction:column;align-items:flex-start;}.time-info{flex-direction:column;gap:8px;}.page-wrap{padding:20px;}}\n" +
                "@media(max-width:480px){.metrics-grid{grid-template-columns:1fr;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"navbar\">\n" +
                "<div class=\"nav-container\">\n" +
                "<div class=\"logo\">\n" +
                "<div class=\"logo-icon\">\n" +
                "<svg viewBox=\"0 0 24 24\"><path d=\"M3 3v18h18\"/><path d=\"M18.7 8l-5.1 5.2-2.8-2.7L7 14.3\"/></svg>\n" +
                "</div>\n" +
                "<div class=\"logo-text\">Texcell LTM 监控管理系统</div>\n" +
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
                "<a href=\"/monitor/dr-auto-fix\">定时任务配置</a>\n" +
                "<a href=\"/monitor/feature-management\">功能模块管理</a>\n" +
                "<a href=\"/monitor/subtask-management\">子任务队列</a>\n" +
                "</div>\n" +
                "<div class=\"user-info\">\n" +
                "<span class=\"user-name\" id=\"currentUsername\">加载中...</span>\n" +
                "<span class=\"user-role\" id=\"currentUserRole\">加载中...</span>\n" +
                "<button class=\"logout-btn\" onclick=\"logout()\">退出</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"page-wrap\">\n" +
                "<div class=\"status-header\">\n" +
                "<div class=\"status-info\">\n" +
                "<div class=\"status-indicator\"><div class=\"status-led\" id=\"statusLed\"></div><span class=\"status-text\" id=\"statusText\">服务运行中</span></div>\n" +
                "<div class=\"time-info\">" +
                "<span><svg viewBox=\"0 0 24 24\"><path d=\"M22 11.08V12a10 10 0 1 1-5.93-9.14\"/><polyline points=\"22 4 12 14.01 9 11.01\"/></svg>启动时间: <span id=\"startTime\">-</span></span>" +
                "<span><svg viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><polyline points=\"12 6 12 12 16 14\"/></svg>已运行: <span id=\"runDuration\">-</span></span>" +
                "<span><svg viewBox=\"0 0 24 24\"><rect x=\"2\" y=\"2\" width=\"20\" height=\"8\" rx=\"2\"/><rect x=\"2\" y=\"14\" width=\"20\" height=\"8\" rx=\"2\"/><line x1=\"6\" y1=\"6\" x2=\"6.01\" y2=\"6\"/><line x1=\"6\" y1=\"18\" x2=\"6.01\" y2=\"18\"/></svg>当前IP: <span id=\"currentIp\">-</span></span>" +
                "</div>\n" +
                "</div>\n" +
                "<button class=\"refresh-btn\" onclick=\"loadAllData()\"><svg viewBox=\"0 0 24 24\"><polyline points=\"23 4 23 10 17 10\"/><polyline points=\"1 20 1 14 7 14\"/><path d=\"M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15\"/></svg>刷新数据</button>\n" +
                "</div>\n" +
                "<div class=\"metrics-grid\" id=\"metricsGrid\"></div>\n" +
                "<div class=\"module-section\"><div class=\"section-title\">API调用监控</div><div class=\"log-card\"><div class=\"log-header\"><span><svg viewBox=\"0 0 24 24\"><path d=\"M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z\"/><polyline points=\"14 2 14 8 20 8\"/><line x1=\"16\" y1=\"13\" x2=\"8\" y2=\"13\"/><line x1=\"16\" y1=\"17\" x2=\"8\" y2=\"17\"/></svg>调用记录</span><span class=\"log-badge\">最近50条 | 每页10条</span></div><div class=\"table-wrapper\"><table class=\"log-table\"><thead><tr><th>时间</th><th>API路径</th><th>耗时(ms)</th><th>状态</th></tr></thead><tbody id=\"apiLogBody\"></tbody></table></div><div class=\"pagination\" id=\"apiPagination\"></div></div></div>\n" +
                "<div class=\"module-section\"><div class=\"section-title\">安全攻击监控</div><div class=\"log-card\"><div class=\"log-header\"><span><svg viewBox=\"0 0 24 24\"><path d=\"M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z\"/></svg>攻击日志</span><span class=\"log-badge-attack\">实时防护 | 每页10条</span></div><div class=\"table-wrapper\"><table class=\"log-table\"><thead><tr><th>时间</th><th>攻击IP</th><th>攻击类型</th><th>目标地址</th><th>详情</th></tr></thead><tbody id=\"attackLogBody\"></tbody></table></div><div class=\"pagination\" id=\"attackPagination\"></div></div></div>\n" +
                "<div class=\"module-section\"><div class=\"section-title\">长短信发送记录</div><div class=\"log-card\"><div class=\"log-header\"><span><svg viewBox=\"0 0 24 24\"><path d=\"M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z\"/><polyline points=\"22,6 12,13 2,6\"/></svg>发送日志</span><span class=\"log-badge\">最近20条 | 每页10条</span></div><div class=\"table-wrapper\"><table class=\"log-table\"><thead><tr><th>时间</th><th>发送记录</th></tr></thead><tbody id=\"longSmsBody\"></tbody></table></div><div class=\"pagination\" id=\"longSmsPagination\"></div></div></div>\n" +
                "<div class=\"module-section\"><div class=\"section-title\">GC异常监控</div><div class=\"log-card\"><div class=\"log-header\"><span><svg viewBox=\"0 0 24 24\"><polyline points=\"3 6 5 6 21 6\"/><path d=\"M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2\"/></svg>垃圾回收日志</span><span class=\"log-badge\">需关注 | 每页10条</span></div><div class=\"table-wrapper\"><table class=\"log-table\"><thead><tr><th>时间</th><th>GC原始日志</th><th>关键说明</th></tr></thead><tbody id=\"gcLogBody\"></tbody></table></div><div class=\"pagination\" id=\"gcPagination\"></div></div></div>\n" +
                "<div class=\"module-section\"><div class=\"section-title\">业务异常监控</div><div class=\"log-card\"><div class=\"log-header\"><span><svg viewBox=\"0 0 24 24\"><path d=\"M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z\"/><line x1=\"12\" y1=\"9\" x2=\"12\" y2=\"13\"/><line x1=\"12\" y1=\"17\" x2=\"12.01\" y2=\"17\"/></svg>异常日志</span><span class=\"log-badge\">最近20条 | 每页10条</span></div><div class=\"table-wrapper\"><table class=\"log-table\"><thead><tr><th>时间</th><th>异常类型</th><th>说明</th></tr></thead><tbody id=\"errorLogBody\"></tbody></table></div><div class=\"pagination\" id=\"errorPagination\"></div></div></div>\n" +
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
                "                var roleText = data.data.role === 'ADMIN' ? '管理员' : 'NOC';\n" +
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
                "function renderAttackTable(){var container=document.getElementById('attackLogBody');if(!container)return;var start=(attackPage-1)*PAGE_SIZE;var end=start+PAGE_SIZE;var pageData=attackData.slice(start,end);var html='';if(pageData.length===0){html='<tr><td colspan=\"5\" class=\"empty\">暂无安全攻击</td></tr>';}else{for(var i=0;i<pageData.length;i++){var attack=pageData[i];var parts=attack.split(' | ');if(parts.length>=4){var time=parts[0].replace(/[\\[\\]]/g,'');var ip=parts[1].replace('IP:','');var type=parts[2].replace('类型:','');var target=parts[3].replace('目标:','');var detail=parts[4]?parts[4].replace('详情:',''):'';html+='<tr><td style=\"white-space:nowrap\">'+time+'</td><td class=\"attack-critical\">'+ip+'</td><td class=\"attack-critical\">'+type+'</td><td>'+escapeHtml(target)+'</td><td>'+escapeHtml(detail)+'</td></tr>';}else{html+='<tr><td colspan=\"5\" class=\"attack-critical\">'+escapeHtml(attack)+'</td></tr>';}}}container.innerHTML=html;var totalPages=Math.ceil(attackData.length/PAGE_SIZE);var dom=document.getElementById('attackPagination');if(!dom)return;if(totalPages<=1){dom.innerHTML='';return;}dom.innerHTML='<button class=\"page-btn\" onclick=\"attackPage=1;renderAttackTable();\"'+(attackPage===1?'disabled':'')+'>首页</button><button class=\"page-btn\" onclick=\"attackPage='+(attackPage-1)+';renderAttackTable();\"'+(attackPage===1?'disabled':'')+'>上一页</button><span class=\"page-info\">第'+attackPage+'/'+totalPages+'页</span><button class=\"page-btn\" onclick=\"attackPage='+(attackPage+1)+';renderAttackTable();\"'+(attackPage===totalPages?'disabled':'')+'>下一页</button><button class=\"page-btn\" onclick=\"attackPage='+totalPages+';renderAttackTable();\"'+(attackPage===totalPages?'disabled':'')+'>尾页</button>';}\n" +
                "function renderLongSmsTable(){var container=document.getElementById('longSmsBody');if(!container)return;var start=(longSmsPage-1)*PAGE_SIZE;var end=start+PAGE_SIZE;var pageData=longSmsData.slice(start,end);var html='';if(pageData.length===0){html='<tr><td colspan=\"2\" class=\"empty\">暂无长短信记录</td></tr>';}else{for(var i=0;i<pageData.length;i++){var log=pageData[i];html+='<tr><td style=\"white-space:nowrap\">'+escapeHtml(log.substring(0,19))+'</td><td style=\"word-break:break-all\">'+escapeHtml(log)+'</td></tr>';}}container.innerHTML=html;var totalPages=Math.ceil(longSmsData.length/PAGE_SIZE);var dom=document.getElementById('longSmsPagination');if(!dom)return;if(totalPages<=1){dom.innerHTML='';return;}dom.innerHTML='<button class=\"page-btn\" onclick=\"longSmsPage=1;renderLongSmsTable();\"'+(longSmsPage===1?'disabled':'')+'>首页</button><button class=\"page-btn\" onclick=\"longSmsPage='+(longSmsPage-1)+';renderLongSmsTable();\"'+(longSmsPage===1?'disabled':'')+'>上一页</button><span class=\"page-info\">第'+longSmsPage+'/'+totalPages+'页</span><button class=\"page-btn\" onclick=\"longSmsPage='+(longSmsPage+1)+';renderLongSmsTable();\"'+(longSmsPage===totalPages?'disabled':'')+'>下一页</button><button class=\"page-btn\" onclick=\"longSmsPage='+totalPages+';renderLongSmsTable();\"'+(longSmsPage===totalPages?'disabled':'')+'>尾页</button>';}\n" +
                "function renderGcTable(){var container=document.getElementById('gcLogBody');if(!container)return;var start=(gcPage-1)*PAGE_SIZE;var end=start+PAGE_SIZE;var pageData=gcData.slice(start,end);var html='';if(pageData.length===0){html='<tr><td colspan=\"3\" class=\"empty\">GC运行正常，无异常</td></tr>';}else{for(var i=0;i<pageData.length;i++){var log=pageData[i];var shortLog=log.length>100?log.substring(0,100)+'...':log;var note=getGcNote(log);html+='<tr><td style=\"white-space:nowrap\">'+log.substring(0,19)+'</td><td class=\"gc-warn\">'+escapeHtml(shortLog)+'</td><td class=\"gc-warn\">'+note+'</td></tr>';}}container.innerHTML=html;var totalPages=Math.ceil(gcData.length/PAGE_SIZE);var dom=document.getElementById('gcPagination');if(!dom)return;if(totalPages<=1){dom.innerHTML='';return;}dom.innerHTML='<button class=\"page-btn\" onclick=\"gcPage=1;renderGcTable();\"'+(gcPage===1?'disabled':'')+'>首页</button><button class=\"page-btn\" onclick=\"gcPage='+(gcPage-1)+';renderGcTable();\"'+(gcPage===1?'disabled':'')+'>上一页</button><span class=\"page-info\">第'+gcPage+'/'+totalPages+'页</span><button class=\"page-btn\" onclick=\"gcPage='+(gcPage+1)+';renderGcTable();\"'+(gcPage===totalPages?'disabled':'')+'>下一页</button><button class=\"page-btn\" onclick=\"gcPage='+totalPages+';renderGcTable();\"'+(gcPage===totalPages?'disabled':'')+'>尾页</button>';}\n" +
                "function renderErrorTable(){var container=document.getElementById('errorLogBody');if(!container)return;var start=(errorPage-1)*PAGE_SIZE;var end=start+PAGE_SIZE;var pageData=errorData.slice(start,end);var html='';if(pageData.length===0){html='<tr><td colspan=\"3\" class=\"empty\">暂无异常</td></tr>';}else{for(var i=0;i<pageData.length;i++){var e=pageData[i];var parts=e.match(/\\[(.*?)\\] \\| \\[(.*?)\\] \\| (.*?) \\| (.*)$/);if(parts){html+='<tr><td style=\"white-space:nowrap\">'+parts[1]+'</td><td class=\"fail\">'+parts[2]+'</td><td>'+escapeHtml(parts[3].substring(0,80))+'</td></tr>';}else{html+='<tr><td colspan=\"3\">'+escapeHtml(e.substring(0,100))+'</td></tr>';}}}container.innerHTML=html;var totalPages=Math.ceil(errorData.length/PAGE_SIZE);var dom=document.getElementById('errorPagination');if(!dom)return;if(totalPages<=1){dom.innerHTML='';return;}dom.innerHTML='<button class=\"page-btn\" onclick=\"errorPage=1;renderErrorTable();\"'+(errorPage===1?'disabled':'')+'>首页</button><button class=\"page-btn\" onclick=\"errorPage='+(errorPage-1)+';renderErrorTable();\"'+(errorPage===1?'disabled':'')+'>上一页</button><span class=\"page-info\">第'+errorPage+'/'+totalPages+'页</span><button class=\"page-btn\" onclick=\"errorPage='+(errorPage+1)+';renderErrorTable();\"'+(errorPage===totalPages?'disabled':'')+'>下一页</button><button class=\"page-btn\" onclick=\"errorPage='+totalPages+';renderErrorTable();\"'+(errorPage===totalPages?'disabled':'')+'>尾页</button>';}\n" +
                "function loadAllData(){fetch('/api/monitor/logs?secret=dn17ORUwf36Znl').then(function(res){return res.json();}).then(function(data){if(data.systemMetrics){var m=data.systemMetrics;var metricsHtml='<div class=\"metric-card\"><div class=\"metric-title\"><svg viewBox=\"0 0 24 24\"><path d=\"M12 2a7 7 0 0 0-7 7c0 2.38 1.19 4.47 3 5.74V17a2 2 0 0 0 2 2h4a2 2 0 0 0 2-2v-2.26c1.81-1.27 3-3.36 3-5.74a7 7 0 0 0-7-7z\"/><line x1=\"9\" y1=\"21\" x2=\"15\" y2=\"21\"/></svg>堆内存使用</div><div class=\"metric-value\">'+(m.heapUsed||0)+'<span class=\"metric-unit\">MB</span></div><div class=\"metric-sub\">最大: '+(m.heapMax||0)+' MB | 使用率: '+Math.round((m.heapUsed||0)/(m.heapMax||1)*100)+'%</div></div><div class=\"metric-card\"><div class=\"metric-title\"><svg viewBox=\"0 0 24 24\"><path d=\"M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z\"/><polyline points=\"3.27 6.96 12 12.01 20.73 6.96\"/><line x1=\"12\" y1=\"22.08\" x2=\"12\" y2=\"12\"/></svg>非堆内存</div><div class=\"metric-value\">'+(m.nonHeapUsed||0)+'<span class=\"metric-unit\">MB</span></div><div class=\"metric-sub\">已加载类: '+formatNumber(m.loadedClasses)+' 个</div></div><div class=\"metric-card\"><div class=\"metric-title\"><svg viewBox=\"0 0 24 24\"><line x1=\"8\" y1=\"6\" x2=\"21\" y2=\"6\"/><line x1=\"8\" y1=\"12\" x2=\"21\" y2=\"12\"/><line x1=\"8\" y1=\"18\" x2=\"21\" y2=\"18\"/><line x1=\"3\" y1=\"6\" x2=\"3.01\" y2=\"6\"/><line x1=\"3\" y1=\"12\" x2=\"3.01\" y2=\"12\"/><line x1=\"3\" y1=\"18\" x2=\"3.01\" y2=\"18\"/></svg>线程状态</div><div class=\"metric-value\">'+formatNumber(m.threadCount)+'<span class=\"metric-unit\">个</span></div><div class=\"metric-sub\">峰值: '+formatNumber(m.peakThreadCount)+' | 守护: '+formatNumber(m.daemonThreadCount)+'</div></div><div class=\"metric-card\"><div class=\"metric-title\"><svg viewBox=\"0 0 24 24\"><polygon points=\"13 2 3 14 12 14 11 22 21 10 12 10 13 2\"/></svg>CPU使用率</div><div class=\"metric-value\">'+(m.cpuUsage||0)+'<span class=\"metric-unit\">%</span></div><div class=\"metric-sub\">CPU核心: '+formatNumber(m.availableProcessors)+' 核</div></div>';var metricsGrid=document.getElementById('metricsGrid');if(metricsGrid)metricsGrid.innerHTML=metricsHtml;}\n" +
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
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=no\">\n" +
                "<title>Texcell LTM WEB 登录审计</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='22' fill='%231677ff'/%3E%3Ctext x='50' y='72' font-size='60' font-weight='bold' text-anchor='middle' fill='white' font-family='Arial'%3ET%3C/text%3E%3C/svg%3E\">\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:ital,wght@0,300;0,400;0,500;0,600;0,700;1,400&display=swap\" rel=\"stylesheet\">\n" +
                "<style>\n" +
                ":root {\n" +
                "  --primary-color:#1677ff;\n" +
                "  --primary-hover:#3c8dff;\n" +
                "  --text-primary:#1e293b;\n" +
                "  --text-secondary:#475569;\n" +
                "  --text-muted:#8a97a8;\n" +
                "  --input-border:#d9e2ec;\n" +
                "  --bg-page:#f5f7fa;\n" +
                "  --bg-card:#ffffff;\n" +
                "  --border-color:#e8ecf1;\n" +
                "  --shadow-sm:0 1px 3px rgba(0,0,0,0.06);\n" +
                "  --shadow-hover:0 4px 12px rgba(22,119,255,0.1);\n" +
                "  --success-color:#52c41a;\n" +
                "  --danger-color:#ff4d4f;\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:var(--bg-page);font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;min-height:100vh;}\n" +
                ".navbar{background:var(--bg-card);box-shadow:var(--shadow-sm);border-bottom:1px solid var(--border-color);position:sticky;top:0;z-index:100;}\n" +
                ".nav-container{max-width:2100px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;padding:0 32px;height:70px;}\n" +
                ".logo{display:flex;align-items:center;gap:12px;}\n" +
                ".logo-icon{width:40px;height:40px;background:linear-gradient(135deg,var(--primary-color) 0%,var(--primary-hover) 100%);border-radius:12px;display:flex;align-items:center;justify-content:center;}\n" +
                ".logo-icon svg{width:22px;height:22px;stroke:#fff;stroke-width:2;fill:none;}\n" +
                ".logo-text{font-size:18px;font-weight:700;color:var(--text-primary);letter-spacing:1px;}\n" +
                ".logo-sub{font-size:11px;color:var(--text-muted);margin-left:8px;}\n" +
                ".menu{display:flex;gap:12px;}\n" +
                ".menu a{color:var(--text-secondary);text-decoration:none;padding:8px 20px;border-radius:40px;transition:all 0.3s;font-weight:500;font-size:14px;}\n" +
                ".menu a:hover{background:#f1f5f9;color:var(--text-primary);}\n" +
                ".menu a.active{background:#e8f0fe;color:var(--primary-color);box-shadow:none;}\n" +
                ".user-info{display:flex;align-items:center;gap:20px;}\n" +
                ".user-name{color:var(--text-secondary);font-size:14px;}\n" +
                ".user-role{background:rgba(22,119,255,0.1);padding:4px 12px;border-radius:20px;font-size:12px;color:var(--primary-color);}\n" +
                ".logout-btn{background:#f1f5f9;border:1px solid var(--border-color);color:var(--text-secondary);padding:8px 20px;border-radius:40px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".page-wrap{max-width:2100px;margin:0 auto;padding:32px;}\n" +
                ".stats-grid{display:grid;grid-template-columns:repeat(5,1fr);gap:20px;margin-bottom:32px;}\n" +
                ".stat-card{background:var(--bg-card);border-radius:12px;padding:24px 20px;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);transition:all 0.3s;}\n" +
                ".stat-card:hover{transform:translateY(-2px);box-shadow:var(--shadow-hover);border-color:var(--primary-color);}\n" +
                ".stat-number{font-size:36px;font-weight:800;color:var(--primary-color);line-height:1;}\n" +
                ".stat-label{font-size:13px;color:var(--text-secondary);margin-top:8px;font-weight:500;}\n" +
                ".stat-sub{font-size:12px;color:var(--text-muted);margin-top:8px;padding-top:8px;border-top:1px solid var(--border-color);}\n" +
                ".search-card{background:var(--bg-card);border-radius:12px;padding:24px;margin-bottom:32px;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);}\n" +
                ".search-row{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-end;margin-bottom:16px;}\n" +
                ".search-row:last-child{margin-bottom:0;}\n" +
                ".search-group{display:flex;flex-direction:column;gap:6px;}\n" +
                ".search-group label{font-size:12px;color:var(--text-secondary);font-weight:500;display:flex;align-items:center;gap:6px;}\n" +
                ".search-group label svg{width:14px;height:14px;stroke:var(--text-muted);stroke-width:2;fill:none;}\n" +
                ".search-group input,.search-group select{padding:10px 12px;border:1px solid var(--input-border);border-radius:10px;font-size:13px;min-width:150px;transition:all 0.3s;background:var(--bg-card);color:var(--text-primary);font-family:inherit;}\n" +
                ".search-group input:focus,.search-group select:focus{outline:none;border-color:var(--primary-color);box-shadow:0 0 0 3px rgba(22,119,255,0.12);}\n" +
                ".search-group input[type=\"datetime-local\"]{min-width:190px;}\n" +
                ".search-actions{flex-direction:row;align-items:center;gap:10px;margin-left:auto;}\n" +
                ".search-btn{background:var(--primary-color);color:white;border:none;padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;display:inline-flex;align-items:center;gap:6px;}\n" +
                ".search-btn:hover{background:var(--primary-hover);box-shadow:0 8px 20px -6px rgba(22,119,255,0.45);}\n" +
                ".search-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".reset-btn{background:#f1f5f9;border:1px solid var(--border-color);padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;color:var(--text-secondary);display:inline-flex;align-items:center;gap:6px;}\n" +
                ".reset-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".reset-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".export-btn{background:var(--success-color);color:white;border:none;padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;display:inline-flex;align-items:center;gap:6px;}\n" +
                ".export-btn:hover{background:#389e0d;box-shadow:0 8px 20px -6px rgba(82,196,26,0.45);}\n" +
                ".export-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".log-card{background:var(--bg-card);border-radius:12px;overflow:hidden;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);}\n" +
                ".table-wrapper{overflow-x:auto;}\n" +
                ".log-table{width:100%;border-collapse:collapse;}\n" +
                ".log-table th{background:#f8fafc;padding:14px 16px;text-align:left;font-size:13px;font-weight:600;color:var(--text-secondary);border-bottom:1px solid var(--border-color);white-space:nowrap;}\n" +
                ".log-table td{padding:14px 16px;font-size:13px;color:var(--text-primary);border-bottom:1px solid var(--border-color);}\n" +
                ".log-table tr:hover td{background:#f8fafc;cursor:pointer;}\n" +
                ".success{color:var(--success-color);font-weight:600;}.fail{color:var(--danger-color);font-weight:600;}\n" +
                ".pagination{display:flex;justify-content:center;align-items:center;gap:12px;padding:16px 20px;border-top:1px solid var(--border-color);background:#f8fafc;flex-wrap:wrap;}\n" +
                ".page-btn{padding:8px 16px;border:1px solid var(--border-color);background:var(--bg-card);color:var(--text-primary);border-radius:40px;cursor:pointer;font-size:13px;transition:all 0.3s;font-weight:500;}\n" +
                ".page-btn:hover:not(:disabled){border-color:var(--primary-color);color:var(--primary-color);transform:translateY(-1px);}\n" +
                ".page-btn:disabled{opacity:0.5;cursor:not-allowed;background:#f1f5f9;}\n" +
                ".page-info{font-size:13px;color:var(--text-muted);}\n" +
                ".badge{display:inline-flex;align-items:center;gap:4px;padding:3px 10px;border-radius:20px;font-size:11px;font-weight:500;}\n" +
                ".badge-google{background:#e8f0fe;color:var(--primary-color);}\n" +
                ".badge-email{background:#e6f7e6;color:var(--success-color);}\n" +
                "@media(max-width:1024px){.stats-grid{grid-template-columns:repeat(3,1fr);}}\n" +
                "@media(max-width:900px){.search-actions{margin-left:0;width:100%;justify-content:flex-start;}}\n" +
                "@media(max-width:768px){.nav-container{padding:0 20px;}.menu a{padding:6px 14px;font-size:12px;}.stats-grid{grid-template-columns:repeat(2,1fr);}.search-group input,.search-group select,.search-group input[type=\"datetime-local\"]{min-width:100%;width:100%;}.search-actions{flex-wrap:wrap;}.page-wrap{padding:20px;}}\n" +
                "@media(max-width:480px){.stats-grid{grid-template-columns:1fr;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"navbar\">\n" +
                "<div class=\"nav-container\">\n" +
                "<div class=\"logo\">\n" +
                "<div class=\"logo-icon\">\n" +
                "<svg viewBox=\"0 0 24 24\"><path d=\"M3 3v18h18\"/><path d=\"M18.7 8l-5.1 5.2-2.8-2.7L7 14.3\"/></svg>\n" +
                "</div>\n" +
                "<div class=\"logo-text\">Texcell LTM 监控管理系统</div>\n" +
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
                "<a href=\"/monitor/dr-auto-fix\">定时任务配置</a>\n" +
                "<a href=\"/monitor/feature-management\">功能模块管理</a>\n" +
                "<a href=\"/monitor/subtask-management\">子任务队列</a>\n" +
                "</div>\n" +
                "<div class=\"user-info\">\n" +
                "<span class=\"user-name\" id=\"currentUsername\">加载中...</span>\n" +
                "<span class=\"user-role\" id=\"currentUserRole\">加载中...</span>\n" +
                "<button class=\"logout-btn\" onclick=\"logout()\">退出</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"page-wrap\">\n" +
                "<div class=\"stats-grid\" id=\"statsGrid\"></div>\n" +
                "<div class=\"search-card\">\n" +
                "<div class=\"search-row\">\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><path d=\"M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2\"/><circle cx=\"12\" cy=\"7\" r=\"4\"/></svg>账号</label><input type=\"text\" id=\"searchUsername\" placeholder=\"模糊查询\"></div>\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><line x1=\"2\" y1=\"12\" x2=\"22\" y2=\"12\"/><path d=\"M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z\"/></svg>IP地址</label><input type=\"text\" id=\"searchIp\" placeholder=\"模糊查询\"></div>\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><path d=\"M22 11.08V12a10 10 0 1 1-5.93-9.14\"/><polyline points=\"22 4 12 14.01 9 11.01\"/></svg>结果</label><select id=\"searchResult\"><option value=\"\">全部</option><option value=\"1\">成功</option><option value=\"0\">失败</option></select></div>\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\"/><path d=\"M7 11V7a5 5 0 0 1 10 0v4\"/></svg>2FA类型</label><select id=\"searchTwoFactor\"><option value=\"\">全部</option><option value=\"GOOGLE\">Google验证器</option><option value=\"EMAIL\">邮箱验证</option></select></div>\n" +
                "</div>\n" +
                "<div class=\"search-row\">\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"4\" width=\"18\" height=\"18\" rx=\"2\" ry=\"2\"/><line x1=\"16\" y1=\"2\" x2=\"16\" y2=\"6\"/><line x1=\"8\" y1=\"2\" x2=\"8\" y2=\"6\"/><line x1=\"3\" y1=\"10\" x2=\"21\" y2=\"10\"/></svg>开始时间</label><input type=\"datetime-local\" id=\"startTime\"></div>\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"4\" width=\"18\" height=\"18\" rx=\"2\" ry=\"2\"/><line x1=\"16\" y1=\"2\" x2=\"16\" y2=\"6\"/><line x1=\"8\" y1=\"2\" x2=\"8\" y2=\"6\"/><line x1=\"3\" y1=\"10\" x2=\"21\" y2=\"10\"/></svg>结束时间</label><input type=\"datetime-local\" id=\"endTime\"></div>\n" +
                "<div class=\"search-group search-actions\"><button class=\"search-btn\" onclick=\"searchLogs()\"><svg viewBox=\"0 0 24 24\"><circle cx=\"11\" cy=\"11\" r=\"8\"/><line x1=\"21\" y1=\"21\" x2=\"16.65\" y2=\"16.65\"/></svg>查询</button><button class=\"reset-btn\" onclick=\"resetSearch()\"><svg viewBox=\"0 0 24 24\"><polyline points=\"23 4 23 10 17 10\"/><polyline points=\"1 20 1 14 7 14\"/><path d=\"M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15\"/></svg>重置</button><button class=\"export-btn\" onclick=\"exportLogs()\"><svg viewBox=\"0 0 24 24\"><path d=\"M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4\"/><polyline points=\"7 10 12 15 17 10\"/><line x1=\"12\" y1=\"15\" x2=\"12\" y2=\"3\"/></svg>导出CSV</button></div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"log-card\">\n" +
                "<div class=\"table-wrapper\">\n" +
                "<table class=\"log-table\"><thead><tr><th>时间</th><th>账号</th><th>IP</th><th>地理位置</th><th>结果</th><th>结果码</th><th>2FA类型</th><th>耗时(ms)</th></tr></thead><tbody id=\"logBody\"><tr><td colspan=\"8\" class=\"empty\" style=\"text-align:center;padding:48px;color:#8a97a8;\">加载中...</td></tr></tbody></table>\n" +
                "</div>\n" +
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
                "                var roleText = data.data.role === 'ADMIN' ? '管理员' : 'NOC';\n" +
                "                roleSpan.innerText = roleText;\n" +
                "            }\n" +
                "        }).catch(function(err) { console.error('获取用户信息失败:', err); });\n" +
                "}\n" +
                "getCurrentUserInfo();\n" +
                "function formatTwoFactor(tf){if(tf==='GOOGLE')return'<span class=\"badge badge-google\">Google</span>';if(tf==='EMAIL')return'<span class=\"badge badge-email\">邮箱</span>';return'-';}\n" +
                "function loadStats(){fetch('/api/monitor/login-stats?secret=dn17ORUwf36Znl').then(res=>res.json()).then(data=>{if(data.code===200&&data.data){var t=data.data.today||{},w=data.data.week||{},m=data.data.month||{},two=data.data.twoFactorStats||[];var google=0,email=0;if(two && two.forEach){two.forEach(s=>{if(s.twoFactorType==='GOOGLE')google=s.totalCount||0;if(s.twoFactorType==='EMAIL')email=s.totalCount||0;});}var successRate=t.total>0?((t.success*100)/t.total).toFixed(1):0;var statsGrid = document.getElementById('statsGrid');if(statsGrid) statsGrid.innerHTML='<div class=\"stat-card\"><div class=\"stat-number\">'+(t.total||0)+'</div><div class=\"stat-label\">今日登录</div><div class=\"stat-sub\">成功 '+(t.success||0)+' / 失败 '+(t.fail||0)+'</div></div><div class=\"stat-card\"><div class=\"stat-number\">'+(w.total||0)+'</div><div class=\"stat-label\">本周登录</div></div><div class=\"stat-card\"><div class=\"stat-number\">'+(m.total||0)+'</div><div class=\"stat-label\">本月登录</div></div><div class=\"stat-card\"><div class=\"stat-number\">'+successRate+'%</div><div class=\"stat-label\">今日成功率</div></div><div class=\"stat-card\"><div class=\"stat-number\">'+google+'/'+email+'</div><div class=\"stat-label\">Google/邮箱2FA</div></div>';}}).catch(err=>console.error('加载统计数据失败:',err));}\n" +
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
                "var url='/api/monitor/login-audit?secret=dn17ORUwf36Znl&page='+page+'&size='+size;if(u)url+='&username='+encodeURIComponent(u);if(ip)url+='&ip='+encodeURIComponent(ip);if(r)url+='&result='+r;if(tf)url+='&twoFactorType='+tf;if(st&&st.trim()!==''){var startTimeStr=st.replace('T',' ');if(startTimeStr.length===16)startTimeStr+=':00';url+='&startTime='+encodeURIComponent(startTimeStr);}if(et&&et.trim()!==''){var endTimeStr=et.replace('T',' ');if(endTimeStr.length===16)endTimeStr+=':00';url+='&endTime='+encodeURIComponent(endTimeStr);}fetch(url).then(res=>res.json()).then(data=>{if(data.code===200){renderTable(data.data||[]);totalPages=data.totalPages||1;renderPagination();}else{var logBody = document.getElementById('logBody');if(logBody) logBody.innerHTML='<tr><td colspan=\"8\" style=\"text-align:center;padding:48px;color:#8a97a8;\">加载失败:'+(data.msg||'错误')+'</td></tr>';}}).catch(function(err){console.error('加载日志失败:',err);var logBody = document.getElementById('logBody');if(logBody) logBody.innerHTML='<tr><td colspan=\"8\" style=\"text-align:center;padding:48px;color:#8a97a8;\">加载失败:网络错误</td></tr>';});}\n" +
                "function renderTable(logs){var logBody = document.getElementById('logBody');if(!logBody) return;if(!logs||logs.length===0){logBody.innerHTML='<tr><td colspan=\"8\" style=\"text-align:center;padding:48px;color:#8a97a8;\">暂无数据</td></tr>';return;}var html='';for(var i=0;i<logs.length;i++){var l=logs[i];var loc=l.ipLocation||(l.ipCountry?l.ipCountry+(l.ipCity?l.ipCity:''):'-');html+='<tr ondblclick=\"showDetailDialog('+l.id+')\" style=\"cursor:pointer;\" title=\"双击查看详情\">'+\n" +
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
                "    modal.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(15,32,64,0.45);z-index:10000;display:flex;align-items:center;justify-content:center;backdrop-filter:blur(4px);-webkit-backdrop-filter:blur(4px);';\n" +
                "    modal.innerHTML = '<div style=\"background:#ffffff;border-radius:16px;max-width:560px;width:90%;max-height:80vh;overflow:hidden;display:flex;flex-direction:column;box-shadow:0 24px 60px -12px rgba(15,45,105,0.25);border:1px solid #e8ecf1;\">' +\n" +
                "        '<div style=\"padding:20px 24px;border-bottom:1px solid #e8ecf1;display:flex;justify-content:space-between;align-items:center;\">' +\n" +
                "        '<h3 style=\"color:#1e293b;margin:0;display:flex;align-items:center;gap:8px;font-size:16px;font-weight:600;\"><svg width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#1677ff\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z\"/><polyline points=\"14 2 14 8 20 8\"/></svg> 登录详情</h3>' +\n" +
                "        '<button onclick=\"closeDetailModal()\" style=\"background:none;border:none;font-size:28px;cursor:pointer;color:#8a97a8;line-height:1;\">&times;</button>' +\n" +
                "        '</div>' +\n" +
                "        '<div style=\"padding:24px;overflow-y:auto;flex:1;\"><div style=\"text-align:center;padding:40px;color:#8a97a8;\">加载中...</div></div>' +\n" +
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
                "        {key:'用户名',icon:'<svg width=\"15\" height=\"15\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#8a97a8\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2\"/><circle cx=\"12\" cy=\"7\" r=\"4\"/></svg>'},\n" +
                "        {key:'登录时间',icon:'<svg width=\"15\" height=\"15\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#8a97a8\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><polyline points=\"12 6 12 12 16 14\"/></svg>'},\n" +
                "        {key:'登录结果',icon:'<svg width=\"15\" height=\"15\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#8a97a8\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M22 11.08V12a10 10 0 1 1-5.93-9.14\"/><polyline points=\"22 4 12 14.01 9 11.01\"/></svg>'},\n" +
                "        {key:'结果码',icon:'<svg width=\"15\" height=\"15\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#8a97a8\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><rect x=\"3\" y=\"3\" width=\"18\" height=\"18\" rx=\"2\"/><line x1=\"9\" y1=\"9\" x2=\"9.01\" y2=\"9\"/><line x1=\"15\" y1=\"9\" x2=\"15.01\" y2=\"9\"/><line x1=\"9\" y1=\"15\" x2=\"15\" y2=\"15\"/></svg>'},\n" +
                "        {key:'结果消息',icon:'<svg width=\"15\" height=\"15\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#8a97a8\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z\"/></svg>'},\n" +
                "        {key:'登录耗时',icon:'<svg width=\"15\" height=\"15\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#8a97a8\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><polyline points=\"12 6 12 12 16 14\"/></svg>'},\n" +
                "        {key:'真实IP',icon:'<svg width=\"15\" height=\"15\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#8a97a8\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><line x1=\"2\" y1=\"12\" x2=\"22\" y2=\"12\"/><path d=\"M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z\"/></svg>'},\n" +
                "        {key:'IP地理位置',icon:'<svg width=\"15\" height=\"15\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#8a97a8\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z\"/><circle cx=\"12\" cy=\"10\" r=\"3\"/></svg>'},\n" +
                "        {key:'请求方式',icon:'<svg width=\"15\" height=\"15\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#8a97a8\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><line x1=\"22\" y1=\"2\" x2=\"11\" y2=\"13\"/><polygon points=\"22 2 15 22 11 13 2 9 22 2\"/></svg>'},\n" +
                "        {key:'请求路径',icon:'<svg width=\"15\" height=\"15\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#8a97a8\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71\"/><path d=\"M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71\"/></svg>'},\n" +
                "        {key:'来源域名',icon:'<svg width=\"15\" height=\"15\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#8a97a8\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z\"/><polyline points=\"9 22 9 12 15 12 15 22\"/></svg>'},\n" +
                "        {key:'2FA类型',icon:'<svg width=\"15\" height=\"15\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#8a97a8\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\"/><path d=\"M7 11V7a5 5 0 0 1 10 0v4\"/></svg>'},\n" +
                "        {key:'2FA目标',icon:'<svg width=\"15\" height=\"15\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#8a97a8\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z\"/><polyline points=\"22,6 12,13 2,6\"/></svg>'},\n" +
                "        {key:'2FA结果',icon:'<svg width=\"15\" height=\"15\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#8a97a8\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M22 11.08V12a10 10 0 1 1-5.93-9.14\"/><polyline points=\"22 4 12 14.01 9 11.01\"/></svg>'},\n" +
                "        {key:'设备信息',icon:'<svg width=\"15\" height=\"15\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#8a97a8\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><rect x=\"5\" y=\"2\" width=\"14\" height=\"20\" rx=\"2\" ry=\"2\"/><line x1=\"12\" y1=\"18\" x2=\"12.01\" y2=\"18\"/></svg>'}\n" +
                "    ];\n" +
                "    for(var i=0;i<fields.length;i++){\n" +
                "        var field = fields[i];\n" +
                "        var value = detail[field.key];\n" +
                "        if(value !== undefined && value !== null && value !== ''){\n" +
                "            html += '<div style=\"display:flex;border-bottom:1px solid #e8ecf1;padding:10px 0;\">' +\n" +
                "                '<div style=\"width:110px;font-weight:500;color:#64748b;display:flex;align-items:center;gap:6px;font-size:13px;\">' +\n" +
                "                field.icon + ' ' + field.key + ':</div>' +\n" +
                "                '<div style=\"flex:1;color:#1e293b;word-break:break-all;font-size:13px;\">' + escapeHtml(String(value)) + '</div>' +\n" +
                "                '</div>';\n" +
                "        }\n" +
                "    }\n" +
                "    html += '</div>';\n" +
                "    container.innerHTML = html;\n" +
                "}\n" +
                "function renderDetailError(msg) {\n" +
                "    var container = document.querySelector('#detailModal > div > div:last-child');\n" +
                "    if(container){\n" +
                "        container.innerHTML = '<div style=\"text-align:center;padding:40px;color:#ff4d4f;\">' + escapeHtml(msg) + '</div>';\n" +
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
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=no\">\n" +
                "<title>Texcell LTM WEB 号码备份</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='22' fill='%231677ff'/%3E%3Ctext x='50' y='72' font-size='60' font-weight='bold' text-anchor='middle' fill='white' font-family='Arial'%3ET%3C/text%3E%3C/svg%3E\">\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:ital,wght@0,300;0,400;0,500;0,600;0,700;1,400&display=swap\" rel=\"stylesheet\">\n" +
                "<style>\n" +
                ":root {\n" +
                "  --primary-color:#1677ff;\n" +
                "  --primary-hover:#3c8dff;\n" +
                "  --text-primary:#1e293b;\n" +
                "  --text-secondary:#475569;\n" +
                "  --text-muted:#8a97a8;\n" +
                "  --input-border:#d9e2ec;\n" +
                "  --bg-page:#f5f7fa;\n" +
                "  --bg-card:#ffffff;\n" +
                "  --border-color:#e8ecf1;\n" +
                "  --shadow-sm:0 1px 3px rgba(0,0,0,0.06);\n" +
                "  --shadow-hover:0 4px 12px rgba(22,119,255,0.1);\n" +
                "  --success-color:#52c41a;\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:var(--bg-page);font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;min-height:100vh;}\n" +
                ".navbar{background:var(--bg-card);box-shadow:var(--shadow-sm);border-bottom:1px solid var(--border-color);position:sticky;top:0;z-index:100;}\n" +
                ".nav-container{max-width:2100px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;padding:0 32px;height:70px;}\n" +
                ".logo{display:flex;align-items:center;gap:12px;}\n" +
                ".logo-icon{width:40px;height:40px;background:linear-gradient(135deg,var(--primary-color) 0%,var(--primary-hover) 100%);border-radius:12px;display:flex;align-items:center;justify-content:center;}\n" +
                ".logo-icon svg{width:22px;height:22px;stroke:#fff;stroke-width:2;fill:none;}\n" +
                ".logo-text{font-size:18px;font-weight:700;color:var(--text-primary);letter-spacing:1px;}\n" +
                ".logo-sub{font-size:11px;color:var(--text-muted);margin-left:8px;}\n" +
                ".menu{display:flex;gap:12px;}\n" +
                ".menu a{color:var(--text-secondary);text-decoration:none;padding:8px 20px;border-radius:40px;transition:all 0.3s;font-weight:500;font-size:14px;}\n" +
                ".menu a:hover{background:#f1f5f9;color:var(--text-primary);}\n" +
                ".menu a.active{background:#e8f0fe;color:var(--primary-color);box-shadow:none;}\n" +
                ".user-info{display:flex;align-items:center;gap:20px;}\n" +
                ".user-name{color:var(--text-secondary);font-size:14px;}\n" +
                ".user-role{background:rgba(22,119,255,0.1);padding:4px 12px;border-radius:20px;font-size:12px;color:var(--primary-color);}\n" +
                ".logout-btn{background:#f1f5f9;border:1px solid var(--border-color);color:var(--text-secondary);padding:8px 20px;border-radius:40px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".page-wrap{max-width:2100px;margin:0 auto;padding:32px;}\n" +
                ".stats-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:20px;margin-bottom:32px;}\n" +
                ".stat-card{background:var(--bg-card);border-radius:12px;padding:24px 20px;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);transition:all 0.3s;display:flex;align-items:center;gap:16px;}\n" +
                ".stat-card:hover{transform:translateY(-2px);box-shadow:var(--shadow-hover);border-color:var(--primary-color);}\n" +
                ".stat-icon{width:48px;height:48px;border-radius:12px;background:rgba(22,119,255,0.1);display:flex;align-items:center;justify-content:center;flex-shrink:0;}\n" +
                ".stat-icon svg{width:24px;height:24px;stroke:var(--primary-color);stroke-width:2;fill:none;}\n" +
                ".stat-info{display:flex;flex-direction:column;}\n" +
                ".stat-number{font-size:28px;font-weight:800;color:var(--text-primary);line-height:1;}\n" +
                ".stat-label{font-size:13px;color:var(--text-secondary);margin-top:6px;font-weight:500;}\n" +
                ".search-card{background:var(--bg-card);border-radius:12px;padding:24px;margin-bottom:32px;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);}\n" +
                ".search-row{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-end;}\n" +
                ".search-group{display:flex;flex-direction:column;gap:6px;}\n" +
                ".search-group label{font-size:12px;color:var(--text-secondary);font-weight:500;display:flex;align-items:center;gap:6px;}\n" +
                ".search-group label svg{width:14px;height:14px;stroke:var(--text-muted);stroke-width:2;fill:none;}\n" +
                ".search-group input,.search-group select{padding:10px 12px;border:1px solid var(--input-border);border-radius:10px;font-size:13px;min-width:180px;transition:all 0.3s;background:var(--bg-card);color:var(--text-primary);font-family:inherit;}\n" +
                ".search-group input:focus,.search-group select:focus{outline:none;border-color:var(--primary-color);box-shadow:0 0 0 3px rgba(22,119,255,0.12);}\n" +
                ".search-group input[type=\"datetime-local\"]{min-width:190px;}\n" +
                ".search-actions{flex-direction:row;align-items:center;gap:10px;margin-left:auto;}\n" +
                ".search-btn{background:var(--primary-color);color:white;border:none;padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;display:inline-flex;align-items:center;gap:6px;}\n" +
                ".search-btn:hover{background:var(--primary-hover);box-shadow:0 8px 20px -6px rgba(22,119,255,0.45);}\n" +
                ".search-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".reset-btn{background:#f1f5f9;border:1px solid var(--border-color);padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;color:var(--text-secondary);display:inline-flex;align-items:center;gap:6px;}\n" +
                ".reset-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".reset-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".log-card{background:var(--bg-card);border-radius:12px;overflow:hidden;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);}\n" +
                ".table-wrapper{overflow-x:auto;}\n" +
                ".log-table{width:100%;border-collapse:collapse;min-width:800px;}\n" +
                ".log-table th{background:#f8fafc;padding:14px 16px;text-align:left;font-size:13px;font-weight:600;color:var(--text-secondary);border-bottom:1px solid var(--border-color);white-space:nowrap;}\n" +
                ".log-table td{padding:14px 16px;font-size:13px;color:var(--text-primary);border-bottom:1px solid var(--border-color);}\n" +
                ".log-table tr:hover td{background:#f8fafc;}\n" +
                ".download-btn{background:var(--success-color);color:white;border:none;padding:8px 16px;border-radius:40px;cursor:pointer;font-size:12px;font-weight:500;transition:all 0.3s;display:inline-flex;align-items:center;gap:6px;}\n" +
                ".download-btn:hover{background:#389e0d;transform:translateY(-1px);box-shadow:0 4px 12px rgba(82,196,26,0.35);}\n" +
                ".download-btn svg{width:14px;height:14px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".pagination{display:flex;justify-content:center;align-items:center;gap:12px;padding:16px 20px;border-top:1px solid var(--border-color);background:#f8fafc;flex-wrap:wrap;}\n" +
                ".page-btn{padding:8px 16px;border:1px solid var(--border-color);background:var(--bg-card);color:var(--text-primary);border-radius:40px;cursor:pointer;font-size:13px;transition:all 0.3s;font-weight:500;}\n" +
                ".page-btn:hover:not(:disabled){border-color:var(--primary-color);color:var(--primary-color);transform:translateY(-1px);}\n" +
                ".page-btn:disabled{opacity:0.5;cursor:not-allowed;background:#f1f5f9;}\n" +
                ".page-info{font-size:13px;color:var(--text-muted);}\n" +
                ".empty{text-align:center;padding:48px;color:var(--text-muted);}\n" +
                ".size-badge{background:#e8f0fe;color:var(--primary-color);padding:3px 10px;border-radius:20px;font-size:12px;display:inline-flex;align-items:center;gap:4px;font-weight:500;}\n" +
                ".size-badge svg{width:12px;height:12px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".file-task-id{font-family:monospace;font-size:12px;color:var(--text-secondary);}\n" +
                ".footer{text-align:center;padding:32px 0;color:var(--text-muted);font-size:12px;border-top:1px solid var(--border-color);margin-top:32px;}\n" +
                "@media(max-width:900px){.search-actions{margin-left:0;width:100%;justify-content:flex-start;}}\n" +
                "@media(max-width:768px){.nav-container{padding:0 20px;}.menu a{padding:6px 14px;font-size:12px;}.search-group input,.search-group select,.search-group input[type=\"datetime-local\"]{min-width:100%;width:100%;}.search-actions{flex-wrap:wrap;}.page-wrap{padding:20px;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"navbar\">\n" +
                "<div class=\"nav-container\">\n" +
                "<div class=\"logo\">\n" +
                "<div class=\"logo-icon\">\n" +
                "<svg viewBox=\"0 0 24 24\"><path d=\"M3 3v18h18\"/><path d=\"M18.7 8l-5.1 5.2-2.8-2.7L7 14.3\"/></svg>\n" +
                "</div>\n" +
                "<div class=\"logo-text\">Texcell LTM 监控管理系统</div>\n" +
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
                "<a href=\"/monitor/dr-auto-fix\">定时任务配置</a>\n" +
                "<a href=\"/monitor/feature-management\">功能模块管理</a>\n" +
                "<a href=\"/monitor/subtask-management\">子任务队列</a>\n" +
                "</div>\n" +
                "<div class=\"user-info\">\n" +
                "<span class=\"user-name\" id=\"currentUsername\">加载中...</span>\n" +
                "<span class=\"user-role\" id=\"currentUserRole\">加载中...</span>\n" +
                "<button class=\"logout-btn\" onclick=\"logout()\">退出</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"page-wrap\">\n" +
                "<div class=\"search-card\">\n" +
                "<div class=\"search-row\">\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><path d=\"M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2\"/><circle cx=\"12\" cy=\"7\" r=\"4\"/></svg>客户账号</label><input type=\"text\" id=\"searchUsername\" placeholder=\"模糊查询\"></div>\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"4\" width=\"18\" height=\"18\" rx=\"2\" ry=\"2\"/><line x1=\"16\" y1=\"2\" x2=\"16\" y2=\"6\"/><line x1=\"8\" y1=\"2\" x2=\"8\" y2=\"6\"/><line x1=\"3\" y1=\"10\" x2=\"21\" y2=\"10\"/></svg>开始时间</label><input type=\"datetime-local\" id=\"startTime\"></div>\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"4\" width=\"18\" height=\"18\" rx=\"2\" ry=\"2\"/><line x1=\"16\" y1=\"2\" x2=\"16\" y2=\"6\"/><line x1=\"8\" y1=\"2\" x2=\"8\" y2=\"6\"/><line x1=\"3\" y1=\"10\" x2=\"21\" y2=\"10\"/></svg>结束时间</label><input type=\"datetime-local\" id=\"endTime\"></div>\n" +
                "<div class=\"search-group search-actions\"><button class=\"search-btn\" onclick=\"searchFiles()\"><svg viewBox=\"0 0 24 24\"><circle cx=\"11\" cy=\"11\" r=\"8\"/><line x1=\"21\" y1=\"21\" x2=\"16.65\" y2=\"16.65\"/></svg>查询</button><button class=\"reset-btn\" onclick=\"resetSearch()\"><svg viewBox=\"0 0 24 24\"><polyline points=\"23 4 23 10 17 10\"/><polyline points=\"1 20 1 14 7 14\"/><path d=\"M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15\"/></svg>重置</button></div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"stats-grid\">\n" +
                "<div class=\"stat-card\">\n" +
                "<div class=\"stat-icon\"><svg viewBox=\"0 0 24 24\"><path d=\"M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z\"/></svg></div>\n" +
                "<div class=\"stat-info\"><span class=\"stat-number\" id=\"totalFiles\">0</span><span class=\"stat-label\">总文件数</span></div>\n" +
                "</div>\n" +
                "<div class=\"stat-card\">\n" +
                "<div class=\"stat-icon\"><svg viewBox=\"0 0 24 24\"><path d=\"M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2\"/><circle cx=\"9\" cy=\"7\" r=\"4\"/><path d=\"M23 21v-2a4 4 0 0 0-3-3.87\"/><path d=\"M16 3.13a4 4 0 0 1 0 7.75\"/></svg></div>\n" +
                "<div class=\"stat-info\"><span class=\"stat-number\" id=\"totalCustomers\">0</span><span class=\"stat-label\">客户数</span></div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"log-card\">\n" +
                "<div class=\"table-wrapper\">\n" +
                "<table class=\"log-table\">\n" +
                "<thead>\n" +
                "<tr><th>任务ID</th><th>客户账号</th><th>文件名</th><th>号码数量</th><th>文件大小</th><th>备份时间</th><th>操作</th></tr>\n" +
                "</thead>\n" +
                "<tbody id=\"fileBody\"><tr><td colspan=\"7\" class=\"empty\">加载中...</td></tr></tbody>\n" +
                "</table>\n" +
                "</div>\n" +
                "<div class=\"pagination\" id=\"pagination\"></div>\n" +
                "</div>\n" +
                "<div class=\"footer\">客户号码文件备份 | 文件保留3天自动清理 | 仅限内部使用</div>\n" +
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
                "                var roleText = data.data.role === 'ADMIN' ? '管理员' : 'NOC';\n" +
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
                "      '<td class=\"file-task-id\">'+escapeHtml(f.taskId||'')+'</td>'+\n" +
                "      '<td><span class=\"size-badge\"><svg viewBox=\"0 0 24 24\"><path d=\"M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2\"/><circle cx=\"12\" cy=\"7\" r=\"4\"/></svg>'+escapeHtml(f.username||'')+'</span></td>'+\n" +
                "      '<td>'+escapeHtml(f.fileName||'')+'</td>'+\n" +
                "      '<td>'+formatNumber(f.numberCount||0)+'</td>'+\n" +
                "      '<td>'+formatFileSize(f.fileSize||0)+'</td>'+\n" +
                "      '<td>'+formatDateTime(f.createTime||'')+'</td>'+\n" +
                "      '<td><button class=\"download-btn\" onclick=\"downloadFile('+(f.id||0)+')\"><svg viewBox=\"0 0 24 24\"><path d=\"M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4\"/><polyline points=\"7 10 12 15 17 10\"/><line x1=\"12\" y1=\"15\" x2=\"12\" y2=\"3\"/></svg>下载</button></td>'+\n" +
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
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=no\">\n" +
                "<title>Texcell LTM WEB API客户管理</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='22' fill='%231677ff'/%3E%3Ctext x='50' y='72' font-size='60' font-weight='bold' text-anchor='middle' fill='white' font-family='Arial'%3ET%3C/text%3E%3C/svg%3E\">\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:ital,wght@0,300;0,400;0,500;0,600;0,700;1,400&display=swap\" rel=\"stylesheet\">\n" +
                "<style>\n" +
                ":root {\n" +
                "  --primary-color:#1677ff;\n" +
                "  --primary-hover:#3c8dff;\n" +
                "  --text-primary:#1e293b;\n" +
                "  --text-secondary:#475569;\n" +
                "  --text-muted:#8a97a8;\n" +
                "  --input-border:#d9e2ec;\n" +
                "  --bg-page:#f5f7fa;\n" +
                "  --bg-card:#ffffff;\n" +
                "  --border-color:#e8ecf1;\n" +
                "  --shadow-sm:0 1px 3px rgba(0,0,0,0.06);\n" +
                "  --shadow-hover:0 4px 12px rgba(22,119,255,0.1);\n" +
                "  --success-color:#52c41a;\n" +
                "  --danger-color:#ff4d4f;\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:var(--bg-page);font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;min-height:100vh;}\n" +
                ".navbar{background:var(--bg-card);box-shadow:var(--shadow-sm);border-bottom:1px solid var(--border-color);position:sticky;top:0;z-index:100;}\n" +
                ".nav-container{max-width:2100px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;padding:0 32px;height:70px;}\n" +
                ".logo{display:flex;align-items:center;gap:12px;}\n" +
                ".logo-icon{width:40px;height:40px;background:linear-gradient(135deg,var(--primary-color) 0%,var(--primary-hover) 100%);border-radius:12px;display:flex;align-items:center;justify-content:center;}\n" +
                ".logo-icon svg{width:22px;height:22px;stroke:#fff;stroke-width:2;fill:none;}\n" +
                ".logo-text{font-size:18px;font-weight:700;color:var(--text-primary);letter-spacing:1px;}\n" +
                ".logo-sub{font-size:11px;color:var(--text-muted);margin-left:8px;}\n" +
                ".menu{display:flex;gap:12px;}\n" +
                ".menu a{color:var(--text-secondary);text-decoration:none;padding:8px 20px;border-radius:40px;transition:all 0.3s;font-weight:500;font-size:14px;}\n" +
                ".menu a:hover{background:#f1f5f9;color:var(--text-primary);}\n" +
                ".menu a.active{background:#e8f0fe;color:var(--primary-color);box-shadow:none;}\n" +
                ".user-info{display:flex;align-items:center;gap:20px;}\n" +
                ".user-name{color:var(--text-secondary);font-size:14px;}\n" +
                ".user-role{background:rgba(22,119,255,0.1);padding:4px 12px;border-radius:20px;font-size:12px;color:var(--primary-color);}\n" +
                ".logout-btn{background:#f1f5f9;border:1px solid var(--border-color);color:var(--text-secondary);padding:8px 20px;border-radius:40px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".page-wrap{max-width:2100px;margin:0 auto;padding:32px;}\n" +
                ".stats-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:20px;margin-bottom:32px;}\n" +
                ".stat-card{background:var(--bg-card);border-radius:12px;padding:24px 20px;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);transition:all 0.3s;display:flex;align-items:center;gap:16px;}\n" +
                ".stat-card:hover{transform:translateY(-2px);box-shadow:var(--shadow-hover);border-color:var(--primary-color);}\n" +
                ".stat-icon{width:48px;height:48px;border-radius:12px;background:rgba(22,119,255,0.1);display:flex;align-items:center;justify-content:center;flex-shrink:0;}\n" +
                ".stat-icon svg{width:24px;height:24px;stroke:var(--primary-color);stroke-width:2;fill:none;}\n" +
                ".stat-icon.success{background:rgba(82,196,26,0.1);}\n" +
                ".stat-icon.success svg{stroke:var(--success-color);}\n" +
                ".stat-icon.danger{background:rgba(255,77,79,0.1);}\n" +
                ".stat-icon.danger svg{stroke:var(--danger-color);}\n" +
                ".stat-info{display:flex;flex-direction:column;}\n" +
                ".stat-number{font-size:28px;font-weight:800;color:var(--text-primary);line-height:1;}\n" +
                ".stat-label{font-size:13px;color:var(--text-secondary);margin-top:6px;font-weight:500;}\n" +
                ".search-card{background:var(--bg-card);border-radius:12px;padding:24px;margin-bottom:32px;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);}\n" +
                ".search-row{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-end;}\n" +
                ".search-group{display:flex;flex-direction:column;gap:6px;}\n" +
                ".search-group label{font-size:12px;color:var(--text-secondary);font-weight:500;display:flex;align-items:center;gap:6px;}\n" +
                ".search-group label svg{width:14px;height:14px;stroke:var(--text-muted);stroke-width:2;fill:none;}\n" +
                ".search-group input,.search-group select{padding:10px 12px;border:1px solid var(--input-border);border-radius:10px;font-size:13px;min-width:180px;transition:all 0.3s;background:var(--bg-card);color:var(--text-primary);font-family:inherit;}\n" +
                ".search-group input:focus,.search-group select:focus{outline:none;border-color:var(--primary-color);box-shadow:0 0 0 3px rgba(22,119,255,0.12);}\n" +
                ".search-actions{display:flex;gap:10px;margin-left:auto;align-items:center;}\n" +
                ".search-btn{background:var(--primary-color);color:white;border:none;padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;display:inline-flex;align-items:center;gap:6px;}\n" +
                ".search-btn:hover{background:var(--primary-hover);box-shadow:0 8px 20px -6px rgba(22,119,255,0.45);}\n" +
                ".search-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".reset-btn{background:#f1f5f9;border:1px solid var(--border-color);padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;color:var(--text-secondary);display:inline-flex;align-items:center;gap:6px;}\n" +
                ".reset-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".reset-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".add-btn{background:var(--success-color);color:white;border:none;padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;display:inline-flex;align-items:center;gap:6px;}\n" +
                ".add-btn:hover{background:#389e0d;box-shadow:0 8px 20px -6px rgba(82,196,26,0.45);}\n" +
                ".add-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".log-card{background:var(--bg-card);border-radius:12px;overflow:hidden;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);}\n" +
                ".table-wrapper{overflow-x:auto;}\n" +
                ".log-table{width:100%;border-collapse:collapse;min-width:800px;}\n" +
                ".log-table th{background:#f8fafc;padding:14px 16px;text-align:left;font-size:13px;font-weight:600;color:var(--text-secondary);border-bottom:1px solid var(--border-color);white-space:nowrap;}\n" +
                ".log-table td{padding:14px 16px;font-size:13px;color:var(--text-primary);border-bottom:1px solid var(--border-color);}\n" +
                ".log-table tr:hover td{background:#f8fafc;}\n" +
                ".status-badge{display:inline-flex;align-items:center;gap:4px;padding:3px 10px;border-radius:20px;font-size:11px;font-weight:500;}\n" +
                ".status-enabled{background:rgba(82,196,26,0.1);color:var(--success-color);}\n" +
                ".status-disabled{background:rgba(255,77,79,0.1);color:var(--danger-color);}\n" +
                ".status-dot{width:6px;height:6px;border-radius:50%;background:currentColor;}\n" +
                ".action-btn{padding:6px 14px;border-radius:40px;cursor:pointer;font-size:12px;transition:all 0.3s;border:none;font-weight:500;display:inline-flex;align-items:center;gap:4px;}\n" +
                ".action-btn svg{width:13px;height:13px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".edit-btn{background:#e8f0fe;color:var(--primary-color);}\n" +
                ".edit-btn:hover{background:var(--primary-color);color:white;transform:translateY(-1px);}\n" +
                ".delete-btn{background:rgba(255,77,79,0.1);color:var(--danger-color);margin-left:8px;}\n" +
                ".delete-btn:hover{background:var(--danger-color);color:white;transform:translateY(-1px);}\n" +
                ".pagination{display:flex;justify-content:center;align-items:center;gap:12px;padding:16px 20px;border-top:1px solid var(--border-color);background:#f8fafc;flex-wrap:wrap;}\n" +
                ".page-btn{padding:8px 16px;border:1px solid var(--border-color);background:var(--bg-card);color:var(--text-primary);border-radius:40px;cursor:pointer;font-size:13px;transition:all 0.3s;font-weight:500;}\n" +
                ".page-btn:hover:not(:disabled){border-color:var(--primary-color);color:var(--primary-color);transform:translateY(-1px);}\n" +
                ".page-btn:disabled{opacity:0.5;cursor:not-allowed;background:#f1f5f9;}\n" +
                ".page-info{font-size:13px;color:var(--text-muted);}\n" +
                ".empty{text-align:center;padding:48px;color:var(--text-muted);}\n" +
                ".footer{text-align:center;padding:32px 0;color:var(--text-muted);font-size:12px;border-top:1px solid var(--border-color);margin-top:32px;}\n" +
                ".modal{position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(15,32,64,0.45);z-index:10000;display:flex;align-items:center;justify-content:center;backdrop-filter:blur(4px);-webkit-backdrop-filter:blur(4px);}\n" +
                ".modal-content{background:var(--bg-card);border-radius:16px;width:520px;max-width:90%;padding:28px;box-shadow:0 24px 60px -12px rgba(15,45,105,0.25);border:1px solid var(--border-color);}\n" +
                ".modal-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:24px;padding-bottom:16px;border-bottom:1px solid var(--border-color);}\n" +
                ".modal-header h3{color:var(--text-primary);margin:0;font-size:17px;font-weight:600;display:flex;align-items:center;gap:8px;}\n" +
                ".modal-header h3 svg{width:18px;height:18px;stroke:var(--primary-color);stroke-width:2;fill:none;}\n" +
                ".modal-close{background:none;border:none;font-size:28px;cursor:pointer;color:var(--text-muted);line-height:1;transition:color 0.2s;}\n" +
                ".modal-close:hover{color:var(--text-primary);}\n" +
                ".form-group{margin-bottom:18px;}\n" +
                ".form-group label{display:block;font-size:13px;font-weight:500;color:var(--text-secondary);margin-bottom:7px;}\n" +
                ".form-group input,.form-group select,.form-group textarea{width:100%;padding:11px 14px;border:1px solid var(--input-border);border-radius:10px;font-size:14px;background:var(--bg-card);color:var(--text-primary);font-family:inherit;transition:border-color .2s,box-shadow .2s;}\n" +
                ".form-group input:focus,.form-group select:focus,.form-group textarea:focus{outline:none;border-color:var(--primary-color);box-shadow:0 0 0 3px rgba(22,119,255,0.12);}\n" +
                ".form-group textarea{resize:vertical;min-height:80px;}\n" +
                ".modal-buttons{display:flex;gap:12px;justify-content:flex-end;margin-top:24px;}\n" +
                ".confirm-btn{background:var(--primary-color);color:white;border:none;padding:10px 28px;border-radius:10px;cursor:pointer;font-weight:500;font-size:14px;transition:all 0.3s;}\n" +
                ".confirm-btn:hover{background:var(--primary-hover);box-shadow:0 8px 20px -6px rgba(22,119,255,0.45);}\n" +
                ".cancel-btn{background:#f1f5f9;border:1px solid var(--border-color);padding:10px 28px;border-radius:10px;cursor:pointer;color:var(--text-secondary);font-weight:500;font-size:14px;transition:all 0.3s;}\n" +
                ".cancel-btn:hover{background:#e8ecf1;}\n" +
                "@media(max-width:900px){.search-actions{margin-left:0;width:100%;justify-content:flex-start;}}\n" +
                "@media(max-width:768px){.nav-container{padding:0 20px;}.menu a{padding:6px 14px;font-size:12px;}.search-group input,.search-group select{min-width:100%;width:100%;}.search-actions{flex-wrap:wrap;}.page-wrap{padding:20px;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"navbar\">\n" +
                "<div class=\"nav-container\">\n" +
                "<div class=\"logo\">\n" +
                "<div class=\"logo-icon\">\n" +
                "<svg viewBox=\"0 0 24 24\"><path d=\"M3 3v18h18\"/><path d=\"M18.7 8l-5.1 5.2-2.8-2.7L7 14.3\"/></svg>\n" +
                "</div>\n" +
                "<div class=\"logo-text\">Texcell LTM 监控管理系统</div>\n" +
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
                "<a href=\"/monitor/dr-auto-fix\">定时任务配置</a>\n" +
                "<a href=\"/monitor/feature-management\">功能模块管理</a>\n" +
                "<a href=\"/monitor/subtask-management\">子任务队列</a>\n" +
                "</div>\n" +
                "<div class=\"user-info\">\n" +
                "<span class=\"user-name\" id=\"currentUsername\">加载中...</span>\n" +
                "<span class=\"user-role\" id=\"currentUserRole\">加载中...</span>\n" +
                "<button class=\"logout-btn\" onclick=\"logout()\">退出</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"page-wrap\">\n" +
                "<div class=\"stats-grid\">\n" +
                "<div class=\"stat-card\">\n" +
                "<div class=\"stat-icon\"><svg viewBox=\"0 0 24 24\"><path d=\"M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2\"/><circle cx=\"9\" cy=\"7\" r=\"4\"/><path d=\"M23 21v-2a4 4 0 0 0-3-3.87\"/><path d=\"M16 3.13a4 4 0 0 1 0 7.75\"/></svg></div>\n" +
                "<div class=\"stat-info\"><span class=\"stat-number\" id=\"totalCount\">0</span><span class=\"stat-label\">总客户数</span></div>\n" +
                "</div>\n" +
                "<div class=\"stat-card\">\n" +
                "<div class=\"stat-icon success\"><svg viewBox=\"0 0 24 24\"><path d=\"M22 11.08V12a10 10 0 1 1-5.93-9.14\"/><polyline points=\"22 4 12 14.01 9 11.01\"/></svg></div>\n" +
                "<div class=\"stat-info\"><span class=\"stat-number\" id=\"activeCount\">0</span><span class=\"stat-label\">已启用</span></div>\n" +
                "</div>\n" +
                "<div class=\"stat-card\">\n" +
                "<div class=\"stat-icon danger\"><svg viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><line x1=\"15\" y1=\"9\" x2=\"9\" y2=\"15\"/><line x1=\"9\" y1=\"9\" x2=\"15\" y2=\"15\"/></svg></div>\n" +
                "<div class=\"stat-info\"><span class=\"stat-number\" id=\"disabledCount\">0</span><span class=\"stat-label\">已禁用</span></div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"search-card\">\n" +
                "<div class=\"search-row\">\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><path d=\"M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2\"/><circle cx=\"12\" cy=\"7\" r=\"4\"/></svg>客户账号</label><input type=\"text\" id=\"searchUsername\" placeholder=\"模糊查询\"></div>\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><path d=\"M12 8v8M8 12h8\"/></svg>状态</label><select id=\"searchStatus\"><option value=\"\">全部</option><option value=\"1\">启用</option><option value=\"0\">禁用</option></select></div>\n" +
                "<div class=\"search-actions\">\n" +
                "<button class=\"search-btn\" onclick=\"searchCustomers()\"><svg viewBox=\"0 0 24 24\"><circle cx=\"11\" cy=\"11\" r=\"8\"/><line x1=\"21\" y1=\"21\" x2=\"16.65\" y2=\"16.65\"/></svg>查询</button>\n" +
                "<button class=\"reset-btn\" onclick=\"resetSearch()\"><svg viewBox=\"0 0 24 24\"><polyline points=\"23 4 23 10 17 10\"/><polyline points=\"1 20 1 14 7 14\"/><path d=\"M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15\"/></svg>重置</button>\n" +
                "<button class=\"add-btn\" onclick=\"showAddModal()\"><svg viewBox=\"0 0 24 24\"><line x1=\"12\" y1=\"5\" x2=\"12\" y2=\"19\"/><line x1=\"5\" y1=\"12\" x2=\"19\" y2=\"12\"/></svg>新增客户</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"log-card\">\n" +
                "<div class=\"table-wrapper\">\n" +
                "<table class=\"log-table\">\n" +
                "<thead>\n" +
                "<tr><th>ID</th><th>客户账号</th><th>密码</th><th>状态</th><th>备注</th><th>创建时间</th><th>操作</th></tr>\n" +
                "</thead>\n" +
                "<tbody id=\"customerBody\"><tr><td colspan=\"7\" class=\"empty\">加载中...</td></tr></tbody>\n" +
                "</table>\n" +
                "</div>\n" +
                "<div class=\"pagination\" id=\"pagination\"></div>\n" +
                "</div>\n" +
                "<div class=\"footer\">API客户管理 | 新增/编辑客户后需重启应用生效</div>\n" +
                "</div>\n" +
                "<div id=\"modal\" class=\"modal\" style=\"display:none;\">\n" +
                "<div class=\"modal-content\">\n" +
                "<div class=\"modal-header\"><h3 id=\"modalTitle\"><svg viewBox=\"0 0 24 24\"><path d=\"M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2\"/><circle cx=\"12\" cy=\"7\" r=\"4\"/></svg>新增客户</h3><button class=\"modal-close\" onclick=\"closeModal()\">&times;</button></div>\n" +
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
                "                var roleText = data.data.role === 'ADMIN' ? '管理员' : 'NOC';\n" +
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
                "'<td><span class=\"status-badge '+statusClass+'\"><span class=\"status-dot\"></span>'+statusText+'</span></td>'+\n" +
                "'<td>'+escapeHtml(c.remark||'')+'</td>'+\n" +
                "'<td>'+formatDateTime(c.createTime||'')+'</td>'+\n" +
                "'<td><button class=\"action-btn edit-btn\" onclick=\"editCustomer('+c.id+')\"><svg viewBox=\"0 0 24 24\"><path d=\"M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7\"/><path d=\"M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z\"/></svg>编辑</button><button class=\"action-btn delete-btn\" onclick=\"deleteCustomer('+c.id+')\"><svg viewBox=\"0 0 24 24\"><polyline points=\"3 6 5 6 21 6\"/><path d=\"M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2\"/></svg>删除</button></td>'+\n" +
                "'</tr>';}tbody.innerHTML=html;}\n" +
                "function formatDateTime(dateStr){if(!dateStr)return'-';return dateStr.replace('T',' ').substring(0,19);}\n" +
                "function renderPagination(){var div=document.getElementById('pagination');if(!div)return;if(totalPages<=1){div.innerHTML='';return;}var html='<button class=\"page-btn\" onclick=\"goPage(1)\"'+(page===1?'disabled':'')+'>首页</button><button class=\"page-btn\" onclick=\"goPage('+(page-1)+')\"'+(page===1?'disabled':'')+'>上一页</button><span class=\"page-info\">第 '+page+' / '+totalPages+' 页</span><button class=\"page-btn\" onclick=\"goPage('+(page+1)+')\"'+(page===totalPages?'disabled':'')+'>下一页</button><button class=\"page-btn\" onclick=\"goPage('+totalPages+')\"'+(page===totalPages?'disabled':'')+'>尾页</button>';div.innerHTML=html;}\n" +
                "function goPage(p){page=p;loadCustomers();}\n" +
                "function showAddModal(){document.getElementById('modalTitle').innerHTML='<svg viewBox=\"0 0 24 24\"><path d=\"M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2\"/><circle cx=\"12\" cy=\"7\" r=\"4\"/></svg>新增客户';document.getElementById('editId').value='';document.getElementById('inputUsername').value='';document.getElementById('inputPassword').value='';document.getElementById('inputStatus').value='1';document.getElementById('inputRemark').value='';document.getElementById('modal').style.display='flex';}\n" +
                "function editCustomer(id){fetch('/api/monitor/customers?secret=dn17ORUwf36Znl&page=1&size=9999').then(res=>res.json()).then(data=>{if(data.code===200){var customer=data.data.find(c=>c.id===id);if(customer){document.getElementById('modalTitle').innerHTML='<svg viewBox=\"0 0 24 24\"><path d=\"M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7\"/><path d=\"M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z\"/></svg>编辑客户';document.getElementById('editId').value=customer.id;document.getElementById('inputUsername').value=customer.username;document.getElementById('inputPassword').value='';document.getElementById('inputStatus').value=customer.status;document.getElementById('inputRemark').value=customer.remark||'';document.getElementById('modal').style.display='flex';}}});}\n" +
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
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=no\">\n" +
                "<title>Texcell LTM WEB 登录账户管理</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='22' fill='%231677ff'/%3E%3Ctext x='50' y='72' font-size='60' font-weight='bold' text-anchor='middle' fill='white' font-family='Arial'%3ET%3C/text%3E%3C/svg%3E\">\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:ital,wght@0,300;0,400;0,500;0,600;0,700;1,400&display=swap\" rel=\"stylesheet\">\n" +
                "<style>\n" +
                ":root {\n" +
                "  --primary-color:#1677ff;\n" +
                "  --primary-hover:#3c8dff;\n" +
                "  --text-primary:#1e293b;\n" +
                "  --text-secondary:#475569;\n" +
                "  --text-muted:#8a97a8;\n" +
                "  --input-border:#d9e2ec;\n" +
                "  --bg-page:#f5f7fa;\n" +
                "  --bg-card:#ffffff;\n" +
                "  --border-color:#e8ecf1;\n" +
                "  --shadow-sm:0 1px 3px rgba(0,0,0,0.06);\n" +
                "  --shadow-hover:0 4px 12px rgba(22,119,255,0.1);\n" +
                "  --success-color:#52c41a;\n" +
                "  --danger-color:#ff4d4f;\n" +
                "  --warn-color:#faad14;\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:var(--bg-page);font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;min-height:100vh;}\n" +
                ".navbar{background:var(--bg-card);box-shadow:var(--shadow-sm);border-bottom:1px solid var(--border-color);position:sticky;top:0;z-index:100;}\n" +
                ".nav-container{max-width:2100px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;padding:0 32px;height:70px;}\n" +
                ".logo{display:flex;align-items:center;gap:12px;}\n" +
                ".logo-icon{width:40px;height:40px;background:linear-gradient(135deg,var(--primary-color) 0%,var(--primary-hover) 100%);border-radius:12px;display:flex;align-items:center;justify-content:center;}\n" +
                ".logo-icon svg{width:22px;height:22px;stroke:#fff;stroke-width:2;fill:none;}\n" +
                ".logo-text{font-size:18px;font-weight:700;color:var(--text-primary);letter-spacing:1px;}\n" +
                ".logo-sub{font-size:11px;color:var(--text-muted);margin-left:8px;}\n" +
                ".menu{display:flex;gap:12px;}\n" +
                ".menu a{color:var(--text-secondary);text-decoration:none;padding:8px 20px;border-radius:40px;transition:all 0.3s;font-weight:500;font-size:14px;}\n" +
                ".menu a:hover{background:#f1f5f9;color:var(--text-primary);}\n" +
                ".menu a.active{background:#e8f0fe;color:var(--primary-color);box-shadow:none;}\n" +
                ".user-info{display:flex;align-items:center;gap:20px;}\n" +
                ".user-name{color:var(--text-secondary);font-size:14px;}\n" +
                ".user-role{background:rgba(22,119,255,0.1);padding:4px 12px;border-radius:20px;font-size:12px;color:var(--primary-color);}\n" +
                ".logout-btn{background:#f1f5f9;border:1px solid var(--border-color);color:var(--text-secondary);padding:8px 20px;border-radius:40px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".page-wrap{max-width:2100px;margin:0 auto;padding:32px;}\n" +
                ".search-card{background:var(--bg-card);border-radius:12px;padding:24px;margin-bottom:32px;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);}\n" +
                ".search-row{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-end;}\n" +
                ".search-group{display:flex;flex-direction:column;gap:6px;}\n" +
                ".search-group label{font-size:12px;color:var(--text-secondary);font-weight:500;display:flex;align-items:center;gap:6px;}\n" +
                ".search-group label svg{width:14px;height:14px;stroke:var(--text-muted);stroke-width:2;fill:none;}\n" +
                ".search-group input,.search-group select{padding:10px 12px;border:1px solid var(--input-border);border-radius:10px;font-size:13px;min-width:180px;transition:all 0.3s;background:var(--bg-card);color:var(--text-primary);font-family:inherit;}\n" +
                ".search-group input:focus,.search-group select:focus{outline:none;border-color:var(--primary-color);box-shadow:0 0 0 3px rgba(22,119,255,0.12);}\n" +
                ".search-actions{display:flex;gap:10px;margin-left:auto;align-items:center;}\n" +
                ".search-btn{background:var(--primary-color);color:white;border:none;padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;display:inline-flex;align-items:center;gap:6px;}\n" +
                ".search-btn:hover{background:var(--primary-hover);box-shadow:0 8px 20px -6px rgba(22,119,255,0.45);}\n" +
                ".search-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".reset-btn{background:#f1f5f9;border:1px solid var(--border-color);padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;color:var(--text-secondary);display:inline-flex;align-items:center;gap:6px;}\n" +
                ".reset-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".reset-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".add-btn{background:var(--success-color);color:white;border:none;padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;display:inline-flex;align-items:center;gap:6px;}\n" +
                ".add-btn:hover{background:#389e0d;box-shadow:0 8px 20px -6px rgba(82,196,26,0.45);}\n" +
                ".add-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".log-card{background:var(--bg-card);border-radius:12px;overflow:hidden;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);}\n" +
                ".table-wrapper{overflow-x:auto;}\n" +
                ".log-table{width:100%;border-collapse:collapse;min-width:900px;}\n" +
                ".log-table th{background:#f8fafc;padding:14px 16px;text-align:left;font-size:13px;font-weight:600;color:var(--text-secondary);border-bottom:1px solid var(--border-color);white-space:nowrap;}\n" +
                ".log-table td{padding:14px 16px;font-size:13px;color:var(--text-primary);border-bottom:1px solid var(--border-color);}\n" +
                ".log-table tr:hover td{background:#f8fafc;}\n" +
                ".status-badge{display:inline-flex;align-items:center;gap:4px;padding:3px 10px;border-radius:20px;font-size:11px;font-weight:500;}\n" +
                ".status-enabled{background:rgba(82,196,26,0.1);color:var(--success-color);}\n" +
                ".status-disabled{background:rgba(255,77,79,0.1);color:var(--danger-color);}\n" +
                ".role-badge{display:inline-flex;align-items:center;gap:4px;padding:3px 10px;border-radius:20px;font-size:11px;font-weight:500;background:#e8f0fe;color:var(--primary-color);}\n" +
                ".role-badge svg{width:12px;height:12px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".status-dot{width:6px;height:6px;border-radius:50%;background:currentColor;}\n" +
                ".action-btn{padding:6px 14px;border-radius:40px;cursor:pointer;font-size:12px;transition:all 0.3s;border:none;font-weight:500;display:inline-flex;align-items:center;gap:4px;}\n" +
                ".action-btn svg{width:13px;height:13px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".edit-btn{background:#e8f0fe;color:var(--primary-color);}\n" +
                ".edit-btn:hover{background:var(--primary-color);color:white;transform:translateY(-1px);}\n" +
                ".delete-btn{background:rgba(255,77,79,0.1);color:var(--danger-color);margin-left:8px;}\n" +
                ".delete-btn:hover{background:var(--danger-color);color:white;transform:translateY(-1px);}\n" +
                ".reset-pwd-btn{background:rgba(250,173,20,0.15);color:var(--warn-color);margin-left:8px;}\n" +
                ".reset-pwd-btn:hover{background:var(--warn-color);color:white;transform:translateY(-1px);}\n" +
                ".pagination{display:flex;justify-content:center;align-items:center;gap:12px;padding:16px 20px;border-top:1px solid var(--border-color);background:#f8fafc;flex-wrap:wrap;}\n" +
                ".page-btn{padding:8px 16px;border:1px solid var(--border-color);background:var(--bg-card);color:var(--text-primary);border-radius:40px;cursor:pointer;font-size:13px;transition:all 0.3s;font-weight:500;}\n" +
                ".page-btn:hover:not(:disabled){border-color:var(--primary-color);color:var(--primary-color);transform:translateY(-1px);}\n" +
                ".page-btn:disabled{opacity:0.5;cursor:not-allowed;background:#f1f5f9;}\n" +
                ".page-info{font-size:13px;color:var(--text-muted);}\n" +
                ".empty{text-align:center;padding:48px;color:var(--text-muted);}\n" +
                ".footer{text-align:center;padding:32px 0;color:var(--text-muted);font-size:12px;border-top:1px solid var(--border-color);margin-top:32px;}\n" +
                ".modal{position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(15,32,64,0.45);z-index:10000;display:flex;align-items:center;justify-content:center;backdrop-filter:blur(4px);-webkit-backdrop-filter:blur(4px);}\n" +
                ".modal-content{background:var(--bg-card);border-radius:16px;width:520px;max-width:90%;padding:28px;box-shadow:0 24px 60px -12px rgba(15,45,105,0.25);border:1px solid var(--border-color);}\n" +
                ".modal-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:24px;padding-bottom:16px;border-bottom:1px solid var(--border-color);}\n" +
                ".modal-header h3{color:var(--text-primary);margin:0;font-size:17px;font-weight:600;display:flex;align-items:center;gap:8px;}\n" +
                ".modal-header h3 svg{width:18px;height:18px;stroke:var(--primary-color);stroke-width:2;fill:none;}\n" +
                ".modal-close{background:none;border:none;font-size:28px;cursor:pointer;color:var(--text-muted);line-height:1;transition:color 0.2s;}\n" +
                ".modal-close:hover{color:var(--text-primary);}\n" +
                ".form-group{margin-bottom:18px;}\n" +
                ".form-group label{display:block;font-size:13px;font-weight:500;color:var(--text-secondary);margin-bottom:7px;}\n" +
                ".form-group input,.form-group select,.form-group textarea{width:100%;padding:11px 14px;border:1px solid var(--input-border);border-radius:10px;font-size:14px;background:var(--bg-card);color:var(--text-primary);font-family:inherit;transition:border-color .2s,box-shadow .2s;}\n" +
                ".form-group input:focus,.form-group select:focus,.form-group textarea:focus{outline:none;border-color:var(--primary-color);box-shadow:0 0 0 3px rgba(22,119,255,0.12);}\n" +
                ".form-group input:disabled,.form-group input[readonly]{background:#f1f5f9;color:var(--text-muted);cursor:not-allowed;}\n" +
                ".form-group textarea{resize:vertical;min-height:60px;}\n" +
                ".modal-buttons{display:flex;gap:12px;justify-content:flex-end;margin-top:24px;}\n" +
                ".confirm-btn{background:var(--primary-color);color:white;border:none;padding:10px 28px;border-radius:10px;cursor:pointer;font-weight:500;font-size:14px;transition:all 0.3s;}\n" +
                ".confirm-btn:hover{background:var(--primary-hover);box-shadow:0 8px 20px -6px rgba(22,119,255,0.45);}\n" +
                ".cancel-btn{background:#f1f5f9;border:1px solid var(--border-color);padding:10px 28px;border-radius:10px;cursor:pointer;color:var(--text-secondary);font-weight:500;font-size:14px;transition:all 0.3s;}\n" +
                ".cancel-btn:hover{background:#e8ecf1;}\n" +
                ".info-text{font-size:12px;color:var(--text-muted);margin-top:6px;}\n" +
                "@media(max-width:900px){.search-actions{margin-left:0;width:100%;justify-content:flex-start;}}\n" +
                "@media(max-width:768px){.nav-container{padding:0 20px;}.menu a{padding:6px 14px;font-size:12px;}.search-group input,.search-group select{min-width:100%;width:100%;}.search-actions{flex-wrap:wrap;}.page-wrap{padding:20px;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"navbar\">\n" +
                "<div class=\"nav-container\">\n" +
                "<div class=\"logo\">\n" +
                "<div class=\"logo-icon\">\n" +
                "<svg viewBox=\"0 0 24 24\"><path d=\"M3 3v18h18\"/><path d=\"M18.7 8l-5.1 5.2-2.8-2.7L7 14.3\"/></svg>\n" +
                "</div>\n" +
                "<div class=\"logo-text\">Texcell LTM 监控管理系统</div>\n" +
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
                "<a href=\"/monitor/dr-auto-fix\">定时任务配置</a>\n" +
                "<a href=\"/monitor/feature-management\">功能模块管理</a>\n" +
                "<a href=\"/monitor/subtask-management\">子任务队列</a>\n" +
                "</div>\n" +
                "<div class=\"user-info\">\n" +
                "<span class=\"user-name\" id=\"currentUsername\">加载中...</span>\n" +
                "<span class=\"user-role\" id=\"currentUserRole\">加载中...</span>\n" +
                "<button class=\"logout-btn\" onclick=\"logout()\">退出</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"page-wrap\">\n" +
                "<div class=\"search-card\">\n" +
                "<div class=\"search-row\">\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><path d=\"M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2\"/><circle cx=\"12\" cy=\"7\" r=\"4\"/></svg>用户名</label><input type=\"text\" id=\"searchUsername\" placeholder=\"模糊查询\"></div>\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><path d=\"M12 8v8M8 12h8\"/></svg>状态</label><select id=\"searchStatus\"><option value=\"\">全部</option><option value=\"1\">启用</option><option value=\"0\">禁用</option></select></div>\n" +
                "<div class=\"search-actions\" id=\"searchActions\">\n" +
                "<button class=\"search-btn\" onclick=\"searchUsers()\"><svg viewBox=\"0 0 24 24\"><circle cx=\"11\" cy=\"11\" r=\"8\"/><line x1=\"21\" y1=\"21\" x2=\"16.65\" y2=\"16.65\"/></svg>查询</button>\n" +
                "<button class=\"reset-btn\" onclick=\"resetSearch()\"><svg viewBox=\"0 0 24 24\"><polyline points=\"23 4 23 10 17 10\"/><polyline points=\"1 20 1 14 7 14\"/><path d=\"M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15\"/></svg>重置</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"log-card\">\n" +
                "<div class=\"table-wrapper\">\n" +
                "<table class=\"log-table\">\n" +
                "<thead>\n" +
                "<tr><th>ID</th><th>用户名</th><th>密码</th><th>角色</th><th>状态</th><th>允许IP</th><th>备注</th><th>创建时间</th><th>操作</th></tr>\n" +
                "</thead>\n" +
                "<tbody id=\"userBody\"><tr><td colspan=\"9\" class=\"empty\">加载中...</td></tr></tbody>\n" +
                "</table>\n" +
                "</div>\n" +
                "<div class=\"pagination\" id=\"pagination\"></div>\n" +
                "</div>\n" +
                "<div class=\"footer\">监控系统登录账户管理 | 支持多IP配置，逗号分隔 | NOC角色只能修改自己的信息和重置密码</div>\n" +
                "</div>\n" +
                "<!-- 编辑用户弹窗 -->\n" +
                "<div id=\"modal\" class=\"modal\" style=\"display:none;\">\n" +
                "<div class=\"modal-content\">\n" +
                "<div class=\"modal-header\"><h3 id=\"modalTitle\"><svg viewBox=\"0 0 24 24\"><path d=\"M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2\"/><circle cx=\"12\" cy=\"7\" r=\"4\"/></svg>编辑用户</h3><button class=\"modal-close\" onclick=\"closeModal()\">&times;</button></div>\n" +
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
                "<div class=\"modal-header\"><h3><svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\"/><path d=\"M7 11V7a5 5 0 0 1 10 0v4\"/></svg>重置密码</h3><button class=\"modal-close\" onclick=\"closeResetPwdModal()\">&times;</button></div>\n" +
                "<input type=\"hidden\" id=\"resetUserId\">\n" +
                "<div class=\"form-group\"><label>用户名</label><input type=\"text\" id=\"resetUsername\" readonly disabled></div>\n" +
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
                "                document.getElementById('currentUserRole').innerText = currentUserRole === 'ADMIN' ? '管理员' : 'NOC';\n" +
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
                "                        addBtn.innerHTML = '<svg viewBox=\"0 0 24 24\"><line x1=\"12\" y1=\"5\" x2=\"12\" y2=\"19\"/><line x1=\"5\" y1=\"12\" x2=\"19\" y2=\"12\"/></svg>新增用户';\n" +
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
                "            actionsHtml += '<button class=\"action-btn edit-btn\" onclick=\"editUser('+u.id+')\"><svg viewBox=\"0 0 24 24\"><path d=\"M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7\"/><path d=\"M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z\"/></svg>编辑</button>';\n" +
                "        }\n" +
                "        if(canResetPwd){\n" +
                "            actionsHtml += '<button class=\"action-btn reset-pwd-btn\" onclick=\"showResetPwdModal('+u.id+',\\''+escapeHtml(u.username)+'\\')\"><svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\"/><path d=\"M7 11V7a5 5 0 0 1 10 0v4\"/></svg>重置密码</button>';\n" +
                "        }\n" +
                "        if(canDelete){\n" +
                "            actionsHtml += '<button class=\"action-btn delete-btn\" onclick=\"deleteUser('+u.id+')\"><svg viewBox=\"0 0 24 24\"><polyline points=\"3 6 5 6 21 6\"/><path d=\"M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2\"/></svg>删除</button>';\n" +
                "        }\n" +
                "        if(actionsHtml === '') actionsHtml = '-';\n" +
                "        \n" +
                "        html+='<tr>'+\n" +
                "            '<td>'+u.id+'</td>'+\n" +
                "            '<td>'+escapeHtml(u.username||'')+'</td>'+\n" +
                "            '<td>******</td>'+\n" +
                "            '<td><span class=\"role-badge\"><svg viewBox=\"0 0 24 24\"><path d=\"M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z\"/></svg>'+escapeHtml(u.role||'ADMIN')+'</span></td>'+\n" +
                "            '<td><span class=\"status-badge '+statusClass+'\"><span class=\"status-dot\"></span>'+statusText+'</span></td>'+\n" +
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
                "    document.getElementById('modalTitle').innerHTML='<svg viewBox=\"0 0 24 24\"><path d=\"M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2\"/><circle cx=\"12\" cy=\"7\" r=\"4\"/></svg>新增用户';\n" +
                "    document.getElementById('editId').value='';\n" +
                "    document.getElementById('inputUsername').value='';\n" +
                "    document.getElementById('inputPassword').value='';\n" +
                "    document.getElementById('inputRole').value='NOC';\n" +
                "    document.getElementById('inputStatus').value='1';\n" +
                "    document.getElementById('inputAllowedIps').value='';\n" +
                "    document.getElementById('inputRemark').value='';\n" +
                "    document.getElementById('roleGroup').style.display = 'block';\n" +
                "    document.getElementById('inputUsername').readOnly = false;\n" +
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
                "                    document.getElementById('modalTitle').innerHTML='<svg viewBox=\"0 0 24 24\"><path d=\"M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7\"/><path d=\"M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z\"/></svg>编辑用户';\n" +
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
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=no\">\n" +
                "<title>Texcell LTM WEB 运维管理</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='22' fill='%231677ff'/%3E%3Ctext x='50' y='72' font-size='60' font-weight='bold' text-anchor='middle' fill='white' font-family='Arial'%3ET%3C/text%3E%3C/svg%3E\">\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:ital,wght@0,300;0,400;0,500;0,600;0,700;1,400&display=swap\" rel=\"stylesheet\">\n" +
                "<script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>\n" +
                "<style>\n" +
                ":root {\n" +
                "  --primary-color:#1677ff;\n" +
                "  --primary-hover:#3c8dff;\n" +
                "  --text-primary:#1e293b;\n" +
                "  --text-secondary:#475569;\n" +
                "  --text-muted:#8a97a8;\n" +
                "  --input-border:#d9e2ec;\n" +
                "  --bg-page:#f5f7fa;\n" +
                "  --bg-card:#ffffff;\n" +
                "  --border-color:#e8ecf1;\n" +
                "  --shadow-sm:0 1px 3px rgba(0,0,0,0.06);\n" +
                "  --shadow-hover:0 4px 12px rgba(22,119,255,0.1);\n" +
                "  --success-color:#52c41a;\n" +
                "  --danger-color:#ff4d4f;\n" +
                "  --warn-color:#faad14;\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:var(--bg-page);font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;min-height:100vh;}\n" +
                ".navbar{background:var(--bg-card);box-shadow:var(--shadow-sm);border-bottom:1px solid var(--border-color);position:sticky;top:0;z-index:100;}\n" +
                ".nav-container{max-width:2100px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;padding:0 32px;height:70px;}\n" +
                ".logo{display:flex;align-items:center;gap:12px;}\n" +
                ".logo-icon{width:40px;height:40px;background:linear-gradient(135deg,var(--primary-color) 0%,var(--primary-hover) 100%);border-radius:12px;display:flex;align-items:center;justify-content:center;}\n" +
                ".logo-icon svg{width:22px;height:22px;stroke:#fff;stroke-width:2;fill:none;}\n" +
                ".logo-text{font-size:18px;font-weight:700;color:var(--text-primary);letter-spacing:1px;}\n" +
                ".logo-sub{font-size:11px;color:var(--text-muted);margin-left:8px;}\n" +
                ".menu{display:flex;gap:12px;}\n" +
                ".menu a{color:var(--text-secondary);text-decoration:none;padding:8px 20px;border-radius:40px;transition:all 0.3s;font-weight:500;font-size:14px;}\n" +
                ".menu a:hover{background:#f1f5f9;color:var(--text-primary);}\n" +
                ".menu a.active{background:#e8f0fe;color:var(--primary-color);box-shadow:none;}\n" +
                ".user-info{display:flex;align-items:center;gap:20px;}\n" +
                ".user-name{color:var(--text-secondary);font-size:14px;}\n" +
                ".user-role{background:rgba(22,119,255,0.1);padding:4px 12px;border-radius:20px;font-size:12px;color:var(--primary-color);}\n" +
                ".logout-btn{background:#f1f5f9;border:1px solid var(--border-color);color:var(--text-secondary);padding:8px 20px;border-radius:40px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".page-wrap{max-width:2100px;margin:0 auto;padding:32px;}\n" +
                ".section-title{font-size:18px;font-weight:700;color:var(--text-primary);margin-bottom:20px;display:flex;align-items:center;gap:8px;}\n" +
                ".section-title::before{content:'';width:4px;height:20px;background:linear-gradient(135deg,var(--primary-color) 0%,var(--primary-hover) 100%);border-radius:4px;}\n" +
                ".cards-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:24px;margin-bottom:32px;}\n" +
                ".card{background:var(--bg-card);border-radius:12px;padding:24px;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);transition:all 0.3s;}\n" +
                ".card:hover{transform:translateY(-2px);box-shadow:var(--shadow-hover);border-color:var(--primary-color);}\n" +
                ".card-header{display:flex;align-items:center;gap:12px;margin-bottom:20px;padding-bottom:12px;border-bottom:1px solid var(--border-color);}\n" +
                ".card-icon{width:44px;height:44px;border-radius:12px;background:rgba(22,119,255,0.1);display:flex;align-items:center;justify-content:center;flex-shrink:0;}\n" +
                ".card-icon svg{width:22px;height:22px;stroke:var(--primary-color);stroke-width:2;fill:none;}\n" +
                ".card-icon.warn{background:rgba(250,173,20,0.1);}\n" +
                ".card-icon.warn svg{stroke:var(--warn-color);}\n" +
                ".card-icon.danger{background:rgba(255,77,79,0.1);}\n" +
                ".card-icon.danger svg{stroke:var(--danger-color);}\n" +
                ".card-title{font-size:16px;font-weight:600;color:var(--text-primary);}\n" +
                ".card-desc{font-size:12px;color:var(--text-muted);margin-top:4px;}\n" +
                ".form-group{margin-bottom:16px;}\n" +
                ".form-group label{display:block;font-size:13px;font-weight:500;color:var(--text-secondary);margin-bottom:7px;}\n" +
                ".form-group input{width:100%;padding:11px 14px;border:1px solid var(--input-border);border-radius:10px;font-size:14px;background:var(--bg-card);color:var(--text-primary);font-family:inherit;transition:border-color .2s,box-shadow .2s;}\n" +
                ".form-group input:focus{outline:none;border-color:var(--primary-color);box-shadow:0 0 0 3px rgba(22,119,255,0.12);}\n" +
                ".card-btn{background:var(--primary-color);color:white;border:none;padding:10px 20px;border-radius:10px;cursor:pointer;font-weight:600;font-size:14px;transition:all 0.3s;width:100%;margin-top:8px;display:inline-flex;align-items:center;justify-content:center;gap:6px;}\n" +
                ".card-btn:hover{background:var(--primary-hover);box-shadow:0 8px 20px -6px rgba(22,119,255,0.45);transform:translateY(-1px);}\n" +
                ".card-btn svg{width:16px;height:16px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".card-btn.danger{background:var(--danger-color);}\n" +
                ".card-btn.danger:hover{background:#ff7875;box-shadow:0 8px 20px -6px rgba(255,77,79,0.45);}\n" +
                ".card-btn.warning{background:var(--warn-color);}\n" +
                ".card-btn.warning:hover{background:#ffc53d;box-shadow:0 8px 20px -6px rgba(250,173,20,0.45);}\n" +
                ".result-area{margin-top:16px;padding:12px 14px;background:#f8fafc;border-radius:10px;font-size:13px;color:var(--text-secondary);display:none;border:1px solid var(--border-color);}\n" +
                ".result-area.show{display:block;}\n" +
                ".result-success{color:var(--success-color);font-weight:500;}\n" +
                ".result-error{color:var(--danger-color);font-weight:500;}\n" +
                ".loading{opacity:0.6;pointer-events:none;}\n" +
                ".stats-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:20px;margin-bottom:32px;}\n" +
                ".stat-card{background:var(--bg-card);border-radius:12px;padding:24px 20px;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);transition:all 0.3s;}\n" +
                ".stat-card:hover{transform:translateY(-2px);box-shadow:var(--shadow-hover);border-color:var(--primary-color);}\n" +
                ".stat-number{font-size:32px;font-weight:800;color:var(--primary-color);line-height:1;}\n" +
                ".stat-label{font-size:13px;color:var(--text-secondary);margin-top:8px;font-weight:500;}\n" +
                ".stat-trend{font-size:12px;margin-top:10px;padding-top:8px;border-top:1px solid var(--border-color);color:var(--text-muted);}\n" +
                ".trend-up{color:var(--success-color);font-weight:600;}\n" +
                ".trend-down{color:var(--danger-color);font-weight:600;}\n" +
                ".chart-row{display:grid;grid-template-columns:1fr 1fr;gap:24px;margin-bottom:32px;}\n" +
                ".chart-card{background:var(--bg-card);border-radius:12px;padding:24px;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);transition:all 0.3s;}\n" +
                ".chart-card:hover{box-shadow:var(--shadow-hover);}\n" +
                ".chart-card h3{font-size:16px;font-weight:600;color:var(--text-primary);margin-bottom:20px;display:flex;align-items:center;gap:8px;}\n" +
                ".chart-card h3 svg{width:18px;height:18px;stroke:var(--primary-color);stroke-width:2;fill:none;}\n" +
                ".chart-container{height:300px;position:relative;}\n" +
                ".search-card{background:var(--bg-card);border-radius:12px;padding:24px;margin-bottom:32px;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);}\n" +
                ".search-row{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-end;}\n" +
                ".search-group{display:flex;flex-direction:column;gap:6px;}\n" +
                ".search-group label{font-size:12px;color:var(--text-secondary);font-weight:500;display:flex;align-items:center;gap:6px;}\n" +
                ".search-group label svg{width:14px;height:14px;stroke:var(--text-muted);stroke-width:2;fill:none;}\n" +
                ".search-group input,.search-group select{padding:10px 12px;border:1px solid var(--input-border);border-radius:10px;font-size:13px;min-width:160px;transition:all 0.3s;background:var(--bg-card);color:var(--text-primary);font-family:inherit;}\n" +
                ".search-group input:focus,.search-group select:focus{outline:none;border-color:var(--primary-color);box-shadow:0 0 0 3px rgba(22,119,255,0.12);}\n" +
                ".search-actions{display:flex;gap:10px;margin-left:auto;align-items:center;}\n" +
                ".search-btn{background:var(--primary-color);color:white;border:none;padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;display:inline-flex;align-items:center;gap:6px;}\n" +
                ".search-btn:hover{background:var(--primary-hover);box-shadow:0 8px 20px -6px rgba(22,119,255,0.45);}\n" +
                ".search-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".reset-btn{background:#f1f5f9;border:1px solid var(--border-color);padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;color:var(--text-secondary);display:inline-flex;align-items:center;gap:6px;}\n" +
                ".reset-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".reset-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".log-card{background:var(--bg-card);border-radius:12px;overflow:hidden;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);}\n" +
                ".table-wrapper{overflow-x:auto;}\n" +
                ".log-table{width:100%;border-collapse:collapse;min-width:800px;}\n" +
                ".log-table th{background:#f8fafc;padding:14px 16px;text-align:left;font-size:13px;font-weight:600;color:var(--text-secondary);border-bottom:1px solid var(--border-color);white-space:nowrap;}\n" +
                ".log-table td{padding:14px 16px;font-size:13px;color:var(--text-primary);border-bottom:1px solid var(--border-color);}\n" +
                ".log-table tr:hover td{background:#f8fafc;}\n" +
                ".status-badge{display:inline-flex;align-items:center;gap:4px;padding:3px 10px;border-radius:20px;font-size:11px;font-weight:500;background:#e8f0fe;color:var(--primary-color);}\n" +
                ".status-success{background:rgba(82,196,26,0.1);color:var(--success-color);}\n" +
                ".status-fail{background:rgba(255,77,79,0.1);color:var(--danger-color);}\n" +
                ".status-dot{width:6px;height:6px;border-radius:50%;background:currentColor;}\n" +
                ".pagination{display:flex;justify-content:center;align-items:center;gap:12px;padding:16px 20px;border-top:1px solid var(--border-color);background:#f8fafc;flex-wrap:wrap;}\n" +
                ".page-btn{padding:8px 16px;border:1px solid var(--border-color);background:var(--bg-card);color:var(--text-primary);border-radius:40px;cursor:pointer;font-size:13px;transition:all 0.3s;font-weight:500;}\n" +
                ".page-btn:hover:not(:disabled){border-color:var(--primary-color);color:var(--primary-color);transform:translateY(-1px);}\n" +
                ".page-btn:disabled{opacity:0.5;cursor:not-allowed;background:#f1f5f9;}\n" +
                ".page-info{font-size:13px;color:var(--text-muted);}\n" +
                ".empty{text-align:center;padding:48px;color:var(--text-muted);}\n" +
                ".footer{text-align:center;padding:32px 0;color:var(--text-muted);font-size:12px;border-top:1px solid var(--border-color);margin-top:32px;}\n" +
                ".action-icon-btn{padding:6px 12px;border-radius:40px;cursor:pointer;font-size:12px;transition:all 0.3s;border:none;font-weight:500;display:inline-flex;align-items:center;gap:4px;}\n" +
                ".action-icon-btn svg{width:13px;height:13px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".rate-limit-section{margin-bottom:32px;}\n" +
                ".section-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:20px;flex-wrap:wrap;gap:12px;}\n" +
                "@media(max-width:1024px){.stats-grid{grid-template-columns:repeat(2,1fr);}}\n" +
                "@media(max-width:768px){.stats-grid{grid-template-columns:1fr;}.chart-row{grid-template-columns:1fr;}.cards-grid{grid-template-columns:1fr;}.nav-container{padding:0 20px;}.menu a{padding:6px 14px;font-size:12px;}.page-wrap{padding:20px;}.search-actions{margin-left:0;width:100%;justify-content:flex-start;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"navbar\">\n" +
                "<div class=\"nav-container\">\n" +
                "<div class=\"logo\">\n" +
                "<div class=\"logo-icon\">\n" +
                "<svg viewBox=\"0 0 24 24\"><path d=\"M3 3v18h18\"/><path d=\"M18.7 8l-5.1 5.2-2.8-2.7L7 14.3\"/></svg>\n" +
                "</div>\n" +
                "<div class=\"logo-text\">Texcell LTM 监控管理系统</div>\n" +
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
                "<a href=\"/monitor/dr-auto-fix\">定时任务配置</a>\n" +
                "<a href=\"/monitor/feature-management\">功能模块管理</a>\n" +
                "<a href=\"/monitor/subtask-management\">子任务队列</a>\n" +
                "</div>\n" +
                "<div class=\"user-info\">\n" +
                "<span class=\"user-name\" id=\"currentUsername\">加载中...</span>\n" +
                "<span class=\"user-role\" id=\"currentUserRole\">加载中...</span>\n" +
                "<button class=\"logout-btn\" onclick=\"logout()\">退出</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"page-wrap\">\n" +
                "<div class=\"section-title\">运维管理工具</div>\n" +
                "<!-- 操作卡片区 -->\n" +
                "<div class=\"cards-grid\">\n" +
                "<div class=\"card\">\n" +
                "<div class=\"card-header\"><div class=\"card-icon\"><svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\"/><path d=\"M7 11V7a5 5 0 0 1 10 0v4\"/></svg></div><div><div class=\"card-title\">重置2FA</div><div class=\"card-desc\">解绑用户的Google验证器，重置后用户需重新绑定</div></div></div>\n" +
                "<div class=\"form-group\"><label>客户账号</label><input type=\"text\" id=\"reset2faUsername\" placeholder=\"请输入客户账号\"></div>\n" +
                "<button class=\"card-btn\" onclick=\"reset2FA()\"><svg viewBox=\"0 0 24 24\"><polyline points=\"23 4 23 10 17 10\"/><polyline points=\"1 20 1 14 7 14\"/><path d=\"M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15\"/></svg>重置2FA</button>\n" +
                "<div id=\"reset2faResult\" class=\"result-area\"></div>\n" +
                "</div>\n" +
                "<div class=\"card\">\n" +
                "<div class=\"card-header\"><div class=\"card-icon warn\"><svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\"/><path d=\"M7 11V7a5 5 0 0 1 9.9-1\"/></svg></div><div><div class=\"card-title\">解除2FA</div><div class=\"card-desc\">解除用户的二次验证，用户将不再需要2FA验证</div></div></div>\n" +
                "<div class=\"form-group\"><label>客户账号</label><input type=\"text\" id=\"disable2faUsername\" placeholder=\"请输入客户账号\"></div>\n" +
                "<button class=\"card-btn warning\" onclick=\"disable2FA()\"><svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\"/><path d=\"M7 11V7a5 5 0 0 1 9.9-1\"/></svg>解除2FA</button>\n" +
                "<div id=\"disable2faResult\" class=\"result-area\"></div>\n" +
                "</div>\n" +
                "<div class=\"card\">\n" +
                "<div class=\"card-header\"><div class=\"card-icon\"><svg viewBox=\"0 0 24 24\"><path d=\"M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2\"/><circle cx=\"12\" cy=\"7\" r=\"4\"/></svg></div><div><div class=\"card-title\">解锁账户</div><div class=\"card-desc\">解除因多次登录失败被锁定的账户</div></div></div>\n" +
                "<div class=\"form-group\"><label>客户账号</label><input type=\"text\" id=\"unlockAccountUsername\" placeholder=\"请输入客户账号\"></div>\n" +
                "<button class=\"card-btn\" onclick=\"unlockAccount()\"><svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\"/><path d=\"M7 11V7a5 5 0 0 1 9.9-1\"/></svg>解锁账户</button>\n" +
                "<div id=\"unlockAccountResult\" class=\"result-area\"></div>\n" +
                "</div>\n" +
                "<div class=\"card\">\n" +
                "<div class=\"card-header\"><div class=\"card-icon danger\"><svg viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><line x1=\"2\" y1=\"12\" x2=\"22\" y2=\"12\"/><path d=\"M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z\"/></svg></div><div><div class=\"card-title\">解锁IP</div><div class=\"card-desc\">解除因多次登录失败被锁定的IP</div></div></div>\n" +
                "<div class=\"form-group\"><label>IP地址或账号</label><input type=\"text\" id=\"unlockIpUsername\" placeholder=\"请输入IP地址\"></div>\n" +
                "<button class=\"card-btn danger\" onclick=\"unlockIp()\"><svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\"/><path d=\"M7 11V7a5 5 0 0 1 9.9-1\"/></svg>解锁IP</button>\n" +
                "<div id=\"unlockIpResult\" class=\"result-area\"></div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<!-- ===== 当前被封禁IP列表 ===== -->\n" +
                "<div class=\"rate-limit-section\">\n" +
                "  <div class=\"section-header\">\n" +
                "    <div class=\"section-title\" style=\"margin-bottom:0;\">当前被封禁IP列表</div>\n" +
                "    <div style=\"display:flex;gap:10px;\">\n" +
                "      <button class=\"search-btn\" style=\"background:var(--danger-color);\" onclick=\"unblockAllBlockedIps()\"><svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\"/><path d=\"M7 11V7a5 5 0 0 1 9.9-1\"/></svg>全部解封</button>\n" +
                "      <button class=\"search-btn\" onclick=\"refreshBlockedIps()\"><svg viewBox=\"0 0 24 24\"><polyline points=\"23 4 23 10 17 10\"/><polyline points=\"1 20 1 14 7 14\"/><path d=\"M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15\"/></svg>刷新</button>\n" +
                "    </div>\n" +
                "  </div>\n" +
                "  <div class=\"log-card\">\n" +
                "    <div class=\"table-wrapper\">\n" +
                "    <table class=\"log-table\">\n" +
                "      <thead>\n" +
                "        <tr>\n" +
                "          <th style=\"width:30%;\">IP地址</th>\n" +
                "          <th style=\"width:25%;\">剩余封禁时间</th>\n" +
                "          <th style=\"width:20%;\">封禁原因</th>\n" +
                "          <th style=\"width:25%;\">操作</th>\n" +
                "        </tr>\n" +
                "      </thead>\n" +
                "      <tbody id=\"blockedIpBody\">\n" +
                "        <tr><td colspan=\"4\" class=\"empty\">加载中...</td></tr>\n" +
                "      </tbody>\n" +
                "    </table>\n" +
                "    </div>\n" +
                "    <div style=\"padding:12px 20px;background:#f8fafc;border-top:1px solid var(--border-color);font-size:12px;color:var(--text-muted);display:flex;align-items:center;gap:6px;\">\n" +
                "      <svg width=\"14\" height=\"14\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><polyline points=\"12 6 12 12 16 14\"/></svg>\n" +
                "      每10秒自动刷新 | 被封禁的IP会在5分钟后自动解封\n" +
                "    </div>\n" +
                "  </div>\n" +
                "</div>\n" +

                "<!-- IP限流配置管理 -->\n" +
                "<div class=\"rate-limit-section\">\n" +
                "  <div class=\"section-header\">\n" +
                "    <div class=\"section-title\" style=\"margin-bottom:0;\">IP限流配置管理</div>\n" +
                "    <button class=\"search-btn\" onclick=\"openAddRateLimit()\"><svg viewBox=\"0 0 24 24\"><line x1=\"12\" y1=\"5\" x2=\"12\" y2=\"19\"/><line x1=\"5\" y1=\"12\" x2=\"19\" y2=\"12\"/></svg>新增配置</button>\n" +
                "  </div>\n" +
                "  <div class=\"log-card\">\n" +
                "    <div class=\"table-wrapper\">\n" +
                "    <table class=\"log-table\">\n" +
                "      <thead>\n" +
                "        <tr>\n" +
                "          <th>IP地址</th>\n" +
                "          <th>客户名称</th>\n" +
                "          <th>限流(次/分钟)</th>\n" +
                "          <th>封禁时长(分钟)</th>\n" +
                "          <th>状态</th>\n" +
                "          <th>优先级</th>\n" +
                "          <th>备注</th>\n" +
                "          <th>操作</th>\n" +
                "        </tr>\n" +
                "      </thead>\n" +
                "      <tbody id=\"rateLimitBody\">\n" +
                "        <tr><td colspan=\"8\" class=\"empty\">加载中...</td></tr>\n" +
                "      </tbody>\n" +
                "    </table>\n" +
                "    </div>\n" +
                "  </div>\n" +
                "</div>\n" +
                // ========== 限流配置弹窗 ==========
                "<div id=\"rateLimitModal\" style=\"position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(15,32,64,0.45);z-index:1000;align-items:center;justify-content:center;display:none;backdrop-filter:blur(4px);-webkit-backdrop-filter:blur(4px);\">\n" +
                "  <div style=\"background:var(--bg-card);border-radius:16px;padding:32px;max-width:560px;width:90%;max-height:90vh;overflow-y:auto;box-shadow:0 24px 60px -12px rgba(15,45,105,0.25);border:1px solid var(--border-color);\">\n" +
                "    <div style=\"display:flex;justify-content:space-between;align-items:center;margin-bottom:24px;padding-bottom:16px;border-bottom:1px solid var(--border-color);\">\n" +
                "      <h3 id=\"modalTitle\" style=\"color:var(--text-primary);font-size:17px;font-weight:600;display:flex;align-items:center;gap:8px;\">新增限流配置</h3>\n" +
                "      <button onclick=\"closeRateLimitModal()\" style=\"background:none;border:none;font-size:28px;cursor:pointer;color:var(--text-muted);line-height:1;\">&times;</button>\n" +
                "    </div>\n" +
                "    <form id=\"rateLimitForm\">\n" +
                "      <input type=\"hidden\" id=\"editId\" value=\"\">\n" +
                "      <div class=\"form-group\">\n" +
                "        <label>IP地址 <span style=\"color:var(--danger-color);\">*</span></label>\n" +
                "        <input type=\"text\" id=\"rlIp\" placeholder=\"例如: 187.124.128.219 或 187.124.128.*\">\n" +
                "        <div style=\"font-size:11px;color:var(--text-muted);margin-top:6px;\">支持通配符 * ，例如 187.124.128.* 匹配该网段所有IP</div>\n" +
                "      </div>\n" +
                "      <div class=\"form-group\">\n" +
                "        <label>客户名称</label>\n" +
                "        <input type=\"text\" id=\"rlCustomerName\" placeholder=\"便于识别的客户名称\">\n" +
                "      </div>\n" +
                "      <div class=\"form-group\">\n" +
                "        <label>每分钟请求限制 <span style=\"color:var(--danger-color);\">*</span></label>\n" +
                "        <input type=\"number\" id=\"rlRateLimit\" value=\"600\" min=\"1\" max=\"9999\">\n" +
                "        <div style=\"font-size:11px;color:var(--text-muted);margin-top:6px;\">默认全局限制为 60 次/分钟</div>\n" +
                "      </div>\n" +
                "      <div class=\"form-group\">\n" +
                "        <label>封禁时长（分钟） <span style=\"color:var(--danger-color);\">*</span></label>\n" +
                "        <input type=\"number\" id=\"rlBlockDuration\" value=\"5\" min=\"1\" max=\"1440\">\n" +
                "      </div>\n" +
                "      <div class=\"form-group\">\n" +
                "        <label>优先级</label>\n" +
                "        <input type=\"number\" id=\"rlPriority\" value=\"0\" min=\"0\" max=\"999\">\n" +
                "        <div style=\"font-size:11px;color:var(--text-muted);margin-top:6px;\">数字越大优先级越高，精确IP匹配优先于通配符</div>\n" +
                "      </div>\n" +
                "      <div class=\"form-group\">\n" +
                "        <label>备注</label>\n" +
                "        <input type=\"text\" id=\"rlRemark\" placeholder=\"例如: 客户XX - 投诉后提高限制\">\n" +
                "      </div>\n" +
                "      <div style=\"display:flex;gap:12px;margin-top:24px;justify-content:flex-end;\">\n" +
                "        <button type=\"button\" class=\"card-btn\" style=\"width:auto;padding:10px 28px;margin-top:0;\" onclick=\"saveRateLimit()\"><svg viewBox=\"0 0 24 24\"><path d=\"M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z\"/><polyline points=\"17 21 17 13 7 13 7 21\"/><polyline points=\"7 3 7 8 15 8\"/></svg>保存</button>\n" +
                "        <button type=\"button\" class=\"reset-btn\" onclick=\"closeRateLimitModal()\" style=\"padding:10px 28px;\">取消</button>\n" +
                "      </div>\n" +
                "    </form>\n" +
                "  </div>\n" +
                "</div>\n" +
                "<!-- 统计卡片区 -->\n" +
                "<div class=\"stats-grid\" id=\"statsGrid\"></div>\n" +
                "<!-- 图表区 -->\n" +
                "<div class=\"chart-row\">\n" +
                "<div class=\"chart-card\"><h3><svg viewBox=\"0 0 24 24\"><path d=\"M3 3v18h18\"/><path d=\"M18.7 8l-5.1 5.2-2.8-2.7L7 14.3\"/></svg>近7天操作趋势</h3><div class=\"chart-container\"><canvas id=\"trendChart\"></canvas></div></div>\n" +
                "<div class=\"chart-card\"><h3><svg viewBox=\"0 0 24 24\"><path d=\"M21.21 15.89A10 10 0 1 1 8 2.83\"/><path d=\"M22 12A10 10 0 0 0 12 2v10z\"/></svg>本月操作类型分布</h3><div class=\"chart-container\"><canvas id=\"typeChart\"></canvas></div></div>\n" +
                "</div>\n" +
                "<div class=\"chart-row\">\n" +
                "<div class=\"chart-card\"><h3><svg viewBox=\"0 0 24 24\"><path d=\"M6 9H4.5a2.5 2.5 0 0 1 0-5H6\"/><path d=\"M18 9h1.5a2.5 2.5 0 0 0 0-5H18\"/><path d=\"M4 22h16\"/><path d=\"M10 14.66V17c0 .55-.47.98-.97 1.21C7.85 18.75 7 20.24 7 22\"/><path d=\"M14 14.66V17c0 .55.47.98.97 1.21C16.15 18.75 17 20.24 17 22\"/><path d=\"M18 2H6v7a6 6 0 0 0 12 0V2z\"/></svg>操作人排行 (本月Top 10)</h3><div class=\"chart-container\"><canvas id=\"operatorChart\"></canvas></div></div>\n" +
                "<div class=\"chart-card\"><h3><svg viewBox=\"0 0 24 24\"><path d=\"M22 11.08V12a10 10 0 1 1-5.93-9.14\"/><polyline points=\"22 4 12 14.01 9 11.01\"/></svg>成功率分析</h3><div class=\"chart-container\"><canvas id=\"successRateChart\"></canvas></div></div>\n" +
                "</div>\n" +
                "<!-- 日志列表区 -->\n" +
                "<div class=\"section-title\">操作解除日志</div>\n" +
                "<div class=\"search-card\">\n" +
                "<div class=\"search-row\">\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><path d=\"M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2\"/><circle cx=\"12\" cy=\"7\" r=\"4\"/></svg>操作人</label><input type=\"text\" id=\"searchOperator\" placeholder=\"模糊查询\"></div>\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><path d=\"M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z\"/></svg>操作类型</label><select id=\"searchOperationType\"><option value=\"\">全部</option><option value=\"RESET_2FA\">重置2FA</option><option value=\"DISABLE_2FA\">解除2FA</option><option value=\"UNLOCK_ACCOUNT\">解锁账户</option><option value=\"UNLOCK_IP\">解锁IP</option><option value=\"RATE_LIMIT_CREATE\">新增限流配置</option><option value=\"RATE_LIMIT_UPDATE\">修改限流配置</option><option value=\"RATE_LIMIT_DELETE\">删除限流配置</option><option value=\"RATE_LIMIT_TOGGLE\">启用/禁用限流配置</option><option value=\"RATE_LIMIT_UNBLOCK\">手动解封IP</option></select></div>\n"+
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"4\" width=\"18\" height=\"18\" rx=\"2\" ry=\"2\"/><line x1=\"16\" y1=\"2\" x2=\"16\" y2=\"6\"/><line x1=\"8\" y1=\"2\" x2=\"8\" y2=\"6\"/><line x1=\"3\" y1=\"10\" x2=\"21\" y2=\"10\"/></svg>开始时间</label><input type=\"datetime-local\" id=\"startTime\"></div>\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"4\" width=\"18\" height=\"18\" rx=\"2\" ry=\"2\"/><line x1=\"16\" y1=\"2\" x2=\"16\" y2=\"6\"/><line x1=\"8\" y1=\"2\" x2=\"8\" y2=\"6\"/><line x1=\"3\" y1=\"10\" x2=\"21\" y2=\"10\"/></svg>结束时间</label><input type=\"datetime-local\" id=\"endTime\"></div>\n" +
                "<div class=\"search-actions\"><button class=\"search-btn\" onclick=\"searchLogs()\"><svg viewBox=\"0 0 24 24\"><circle cx=\"11\" cy=\"11\" r=\"8\"/><line x1=\"21\" y1=\"21\" x2=\"16.65\" y2=\"16.65\"/></svg>查询</button><button class=\"reset-btn\" onclick=\"resetSearch()\"><svg viewBox=\"0 0 24 24\"><polyline points=\"23 4 23 10 17 10\"/><polyline points=\"1 20 1 14 7 14\"/><path d=\"M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15\"/></svg>重置</button></div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"log-card\">\n" +
                "<div class=\"table-wrapper\">\n" +
                "<table class=\"log-table\">\n" +
                "<thead>\n" +
                "<tr><th>时间</th><th>操作人</th><th>操作类型</th><th>操作目标</th><th>结果</th><th>结果消息</th><th>IP</th></tr>\n" +
                "</thead>\n" +
                "<tbody id=\"logBody\"><tr><td colspan=\"7\" class=\"empty\">加载中...</td></tr></tbody>\n" +
                "</table>\n" +
                "</div>\n" +
                "<div class=\"pagination\" id=\"pagination\"></div>\n" +
                "</div>\n" +
                "<div class=\"footer\">EC WEB运维管理 | 操作请谨慎，所有操作均有日志记录 | 仅限内部使用</div>\n" +
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
                "                var roleText = data.data.role === 'ADMIN' ? '管理员' : 'NOC';\n" +
                "                roleSpan.innerText = roleText;\n" +
                "            }\n" +
                "        }).catch(function(err) { console.error('获取用户信息失败:', err); });\n" +
                "}\n" +
                "getCurrentUserInfo();\n" +
                "\n" +
                "function showResult(elementId, success, message){\n" +
                "    var el = document.getElementById(elementId);\n" +
                "    if(!el) return;\n" +
                "    var icon = success ? '<svg width=\"15\" height=\"15\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#52c41a\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" style=\"display:inline;vertical-align:-2px;margin-right:4px;\"><path d=\"M22 11.08V12a10 10 0 1 1-5.93-9.14\"/><polyline points=\"22 4 12 14.01 9 11.01\"/></svg>' : '<svg width=\"15\" height=\"15\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#ff4d4f\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" style=\"display:inline;vertical-align:-2px;margin-right:4px;\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><line x1=\"15\" y1=\"9\" x2=\"9\" y2=\"15\"/><line x1=\"9\" y1=\"9\" x2=\"15\" y2=\"15\"/></svg>';\n" +
                "    el.innerHTML = '<span class=\"' + (success ? 'result-success' : 'result-error') + '\">' + icon + escapeHtml(message) + '</span>';\n" +
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
                "// ===== 被封禁IP列表 =====\n" +
                "function loadBlockedIps() {\n" +
                "    fetch('/api/monitor/rate-limit/blocked-list?secret=dn17ORUwf36Znl')\n" +
                "        .then(function(res) { return res.json(); })\n" +
                "        .then(function(data) {\n" +
                "            if (data.code === 200) {\n" +
                "                renderBlockedIpTable(data.data || []);\n" +
                "            } else {\n" +
                "                document.getElementById('blockedIpBody').innerHTML = \n" +
                "                    '<tr><td colspan=\"4\" class=\"empty\">加载失败: ' + (data.msg || '错误') + '</td></tr>';\n" +
                "            }\n" +
                "        }).catch(function(err) {\n" +
                "            document.getElementById('blockedIpBody').innerHTML = \n" +
                "                '<tr><td colspan=\"4\" class=\"empty\">加载失败: 网络错误</td></tr>';\n" +
                "        });\n" +
                "}\n" +
                "\n" +
                "function renderBlockedIpTable(list) {\n" +
                "    var tbody = document.getElementById('blockedIpBody');\n" +
                "    if (!list || list.length === 0) {\n" +
                "        tbody.innerHTML = '<tr><td colspan=\"4\" class=\"empty\">当前没有IP被封禁，一切正常</td></tr>';\n" +
                "        return;\n" +
                "    }\n" +
                "    var html = '';\n" +
                "    for (var i = 0; i < list.length; i++) {\n" +
                "        var item = list[i];\n" +
                "        var remaining = item.remainingSeconds;\n" +
                "        var timeText = formatRemainingTime(remaining);\n" +
                "        html += '<tr>' +\n" +
                "            '<td><code style=\"background:#f1f5f9;padding:3px 10px;border-radius:6px;font-weight:600;color:#ff4d4f;\">' + escapeHtml(item.ip) + '</code></td>' +\n" +
                "            '<td><span style=\"color:#ff4d4f;font-weight:700;font-size:15px;\">' + timeText + '</span></td>' +\n" +
                "            '<td><span style=\"color:#64748b;\">超过请求频率限制</span></td>' +\n" +
                "            '<td><button class=\"action-icon-btn\" style=\"background:rgba(255,77,79,0.1);color:#ff4d4f;\" onclick=\"unblockRateLimit(\\'' + item.ip + '\\')\"><svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\"/><path d=\"M7 11V7a5 5 0 0 1 9.9-1\"/></svg>解封</button></td>' +\n" +
                "            '</tr>';\n" +
                "    }\n" +
                "    tbody.innerHTML = html;\n" +
                "}\n" +
                "\n" +
                "function formatRemainingTime(seconds) {\n" +
                "    if (seconds <= 0) return '即将解封';\n" +
                "    var mins = Math.floor(seconds / 60);\n" +
                "    var secs = Math.floor(seconds % 60);\n" +
                "    if (mins > 0) {\n" +
                "        return mins + ' 分 ' + secs + ' 秒';\n" +
                "    }\n" +
                "    return secs + ' 秒';\n" +
                "}\n" +
                "\n" +
                "function refreshBlockedIps() {\n" +
                "    loadBlockedIps();\n" +
                "    showToast('已刷新', 'success');\n" +
                "}\n" +
                "\n" +
                "function unblockRateLimit(ip) {\n" +
                "    if (!confirm('确认要解封 IP: ' + ip + ' 吗？')) return;\n" +
                "    \n" +
                "    fetch('/api/monitor/rate-limit/unblock?ip=' + encodeURIComponent(ip) + '&secret=dn17ORUwf36Znl', {\n" +
                "        method: 'POST'\n" +
                "    }).then(function(res) { return res.json(); })\n" +
                "      .then(function(result) {\n" +
                "          if (result.code === 200) {\n" +
                "              showToast(result.msg, 'success');\n" +
                "              loadBlockedIps();\n" +
                "              loadRateLimitConfigs();\n" +
                "          } else {\n" +
                "              showToast(result.msg, 'error');\n" +
                "          }\n" +
                "      }).catch(function(err) {\n" +
                "          showToast('网络错误: ' + err.message, 'error');\n" +
                "      });\n" +
                "}\n" +
                "\n" +
                "function unblockAllBlockedIps() {\n" +
                "    var tbody = document.getElementById('blockedIpBody');\n" +
                "    var hasBlocked = tbody && tbody.innerHTML.indexOf('当前没有IP被封禁') === -1;\n" +
                "    if (!hasBlocked) {\n" +
                "        showToast('当前没有IP需要解封', 'info');\n" +
                "        return;\n" +
                "    }\n" +
                "    if (!confirm('确认要解封所有被封禁的IP吗？')) return;\n" +
                "    \n" +
                "    fetch('/api/monitor/rate-limit/unblock-all?secret=dn17ORUwf36Znl', {\n" +
                "        method: 'POST'\n" +
                "    }).then(function(res) { return res.json(); })\n" +
                "      .then(function(result) {\n" +
                "          if (result.code === 200) {\n" +
                "              showToast(result.msg, 'success');\n" +
                "              loadBlockedIps();\n" +
                "              loadRateLimitConfigs();\n" +
                "          } else {\n" +
                "              showToast(result.msg, 'error');\n" +
                "          }\n" +
                "      });\n" +
                "}\n" +
                "\n" +
                "function showToast(message, type) {\n" +
                "    var toast = document.createElement('div');\n" +
                "    toast.style.cssText = \n" +
                "        'position:fixed;top:20px;right:20px;padding:12px 24px;border-radius:10px;color:#fff;font-weight:500;z-index:9999;' +\n" +
                "        'box-shadow:0 8px 20px -6px rgba(0,0,0,0.25);animation:slideIn 0.3s ease;' +\n" +
                "        (type === 'success' ? 'background:#52c41a;' : \n" +
                "         type === 'error' ? 'background:#ff4d4f;' : \n" +
                "         'background:#1677ff;');\n" +
                "    toast.textContent = message;\n" +
                "    document.body.appendChild(toast);\n" +
                "    setTimeout(function() {\n" +
                "        toast.style.opacity = '0';\n" +
                "        toast.style.transition = 'opacity 0.3s';\n" +
                "        setTimeout(function() { toast.remove(); }, 300);\n" +
                "    }, 3000);\n" +
                "}\n" +
                "\n" +
                "loadBlockedIps();\n" +
                "setInterval(function(){ loadBlockedIps(); }, 10000);\n" +
                "\n"+

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
                "// ============ IP限流配置管理 ============\n" +
                "function loadRateLimitConfigs() {\n" +
                "    fetch('/api/monitor/rate-limit/list?secret=dn17ORUwf36Znl')\n" +
                "        .then(function(res) { return res.json(); })\n" +
                "        .then(function(data) {\n" +
                "            if (data.code === 200) {\n" +
                "                renderRateLimitTable(data.data || [], data.defaultRateLimit);\n" +
                "            } else {\n" +
                "                document.getElementById('rateLimitBody').innerHTML = \n" +
                "                    '<tr><td colspan=\"8\" class=\"empty\">加载失败: ' + (data.msg || '错误') + '</td></tr>';\n" +
                "            }\n" +
                "        }).catch(function(err) {\n" +
                "            document.getElementById('rateLimitBody').innerHTML = \n" +
                "                '<tr><td colspan=\"8\" class=\"empty\">加载失败: 网络错误</td></tr>';\n" +
                "        });\n" +
                "}\n" +
                "\n" +
                "function renderRateLimitTable(list, defaultRate) {\n" +
                "    var tbody = document.getElementById('rateLimitBody');\n" +
                "    if (!list || list.length === 0) {\n" +
                "        tbody.innerHTML = '<tr><td colspan=\"8\" class=\"empty\">暂无配置，使用默认限流 ' + defaultRate + ' 次/分钟</td></tr>';\n" +
                "        return;\n" +
                "    }\n" +
                "    var html = '';\n" +
                "    for (var i = 0; i < list.length; i++) {\n" +
                "        var cfg = list[i];\n" +
                "        var statusHtml = cfg.enabled === 1 ? \n" +
                "            '<span class=\"status-badge status-success\"><span class=\"status-dot\"></span>已启用</span>' : \n" +
                "            '<span class=\"status-badge status-fail\"><span class=\"status-dot\"></span>已禁用</span>';\n" +
                "        html += '<tr>' +\n" +
                "            '<td><code style=\"background:#f1f5f9;padding:3px 10px;border-radius:6px;color:var(--text-primary);\">' + escapeHtml(cfg.ip) + '</code></td>' +\n" +
                "            '<td>' + escapeHtml(cfg.customerName || '-') + '</td>' +\n" +
                "            '<td><strong>' + cfg.rateLimit + '</strong> 次/分钟</td>' +\n" +
                "            '<td>' + cfg.blockDuration + ' 分钟</td>' +\n" +
                "            '<td>' + statusHtml + '</td>' +\n" +
                "            '<td>' + (cfg.priority || 0) + '</td>' +\n" +
                "            '<td style=\"max-width:150px;overflow:hidden;text-overflow:ellipsis;\">' + (cfg.remark || '-') + '</td>' +\n" +
                "            '<td>' +\n" +
                "            '<button class=\"action-icon-btn\" style=\"background:#e8f0fe;color:#1677ff;margin-right:4px;\" onclick=\"editRateLimit(' + cfg.id + ')\"><svg viewBox=\"0 0 24 24\"><path d=\"M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7\"/><path d=\"M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z\"/></svg>编辑</button>' +\n" +
                "            '<button class=\"action-icon-btn\" style=\"background:' + (cfg.enabled === 1 ? 'rgba(250,173,20,0.15);color:#faad14;' : 'rgba(82,196,26,0.1);color:#52c41a;') + 'margin-right:4px;\" onclick=\"toggleRateLimit(' + cfg.id + ',' + (cfg.enabled === 1 ? 0 : 1) + ')\">' + (cfg.enabled === 1 ? '<svg viewBox=\"0 0 24 24\"><rect x=\"6\" y=\"4\" width=\"4\" height=\"16\"/><rect x=\"14\" y=\"4\" width=\"4\" height=\"16\"/></svg>禁用' : '<svg viewBox=\"0 0 24 24\"><polygon points=\"5 3 19 12 5 21 5 3\"/></svg>启用') + '</button>' +\n" +
                "            '<button class=\"action-icon-btn\" style=\"background:rgba(255,77,79,0.1);color:#ff4d4f;\" onclick=\"deleteRateLimit(' + cfg.id + ')\"><svg viewBox=\"0 0 24 24\"><polyline points=\"3 6 5 6 21 6\"/><path d=\"M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2\"/></svg>删除</button>' +\n" +
                "            '</td>' +\n" +
                "            '</tr>';\n" +
                "    }\n" +
                "    tbody.innerHTML = html;\n" +
                "}\n" +
                "\n" +
                "function openAddRateLimit() {\n" +
                "    document.getElementById('modalTitle').innerText = '新增限流配置';\n" +
                "    document.getElementById('editId').value = '';\n" +
                "    document.getElementById('rlIp').value = '';\n" +
                "    document.getElementById('rlCustomerName').value = '';\n" +
                "    document.getElementById('rlRateLimit').value = 600;\n" +
                "    document.getElementById('rlBlockDuration').value = 5;\n" +
                "    document.getElementById('rlPriority').value = 0;\n" +
                "    document.getElementById('rlRemark').value = '';\n" +
                "    document.getElementById('rateLimitModal').style.display = 'flex';\n" +
                "}\n" +
                "\n" +
                "function closeRateLimitModal() {\n" +
                "    document.getElementById('rateLimitModal').style.display = 'none';\n" +
                "}\n" +
                "\n" +
                "function editRateLimit(id) {\n" +
                "    fetch('/api/monitor/rate-limit/list?secret=dn17ORUwf36Znl')\n" +
                "        .then(function(res) { return res.json(); })\n" +
                "        .then(function(data) {\n" +
                "            if (data.code === 200) {\n" +
                "                var list = data.data || [];\n" +
                "                var cfg = null;\n" +
                "                for (var i = 0; i < list.length; i++) {\n" +
                "                    if (list[i].id === id) {\n" +
                "                        cfg = list[i];\n" +
                "                        break;\n" +
                "                    }\n" +
                "                }\n" +
                "                if (cfg) {\n" +
                "                    document.getElementById('modalTitle').innerText = '编辑限流配置 - ' + cfg.ip;\n" +
                "                    document.getElementById('editId').value = cfg.id;\n" +
                "                    document.getElementById('rlIp').value = cfg.ip;\n" +
                "                    document.getElementById('rlCustomerName').value = cfg.customerName || '';\n" +
                "                    document.getElementById('rlRateLimit').value = cfg.rateLimit;\n" +
                "                    document.getElementById('rlBlockDuration').value = cfg.blockDuration;\n" +
                "                    document.getElementById('rlPriority').value = cfg.priority || 0;\n" +
                "                    document.getElementById('rlRemark').value = cfg.remark || '';\n" +
                "                    document.getElementById('rateLimitModal').style.display = 'flex';\n" +
                "                }\n" +
                "            }\n" +
                "        });\n" +
                "}\n" +
                "\n" +
                "function saveRateLimit() {\n" +
                "    var id = document.getElementById('editId').value;\n" +
                "    var ip = document.getElementById('rlIp').value.trim();\n" +
                "    var customerName = document.getElementById('rlCustomerName').value.trim();\n" +
                "    var rateLimit = parseInt(document.getElementById('rlRateLimit').value);\n" +
                "    var blockDuration = parseInt(document.getElementById('rlBlockDuration').value);\n" +
                "    var priority = parseInt(document.getElementById('rlPriority').value) || 0;\n" +
                "    var remark = document.getElementById('rlRemark').value.trim();\n" +
                "\n" +
                "    if (!ip) { alert('请输入IP地址'); return; }\n" +
                "    if (!rateLimit || rateLimit < 1) { alert('请输入有效的限流值'); return; }\n" +
                "    if (!blockDuration || blockDuration < 1) { alert('请输入有效的封禁时长'); return; }\n" +
                "\n" +
                "    var data = {\n" +
                "        id: id || null,\n" +
                "        ip: ip,\n" +
                "        customerName: customerName,\n" +
                "        rateLimit: rateLimit,\n" +
                "        blockDuration: blockDuration,\n" +
                "        priority: priority,\n" +
                "        remark: remark,\n" +
                "        enabled: 1\n" +
                "    };\n" +
                "\n" +
                "    fetch('/api/monitor/rate-limit/save?secret=dn17ORUwf36Znl', {\n" +
                "        method: 'POST',\n" +
                "        headers: { 'Content-Type': 'application/json' },\n" +
                "        body: JSON.stringify(data)\n" +
                "    }).then(function(res) { return res.json(); })\n" +
                "      .then(function(result) {\n" +
                "          if (result.code === 200) {\n" +
                "              alert(result.msg);\n" +
                "              closeRateLimitModal();\n" +
                "              loadRateLimitConfigs();\n" +
                "          } else {\n" +
                "              alert(result.msg);\n" +
                "          }\n" +
                "      }).catch(function(err) {\n" +
                "          alert('网络错误: ' + err.message);\n" +
                "      });\n" +
                "}\n" +
                "\n" +
                "function toggleRateLimit(id, enabled) {\n" +
                "    var action = enabled === 1 ? '启用' : '禁用';\n" +
                "    if (!confirm('确认要' + action + '该配置吗？')) return;\n" +
                "\n" +
                "    fetch('/api/monitor/rate-limit/toggle?id=' + id + '&enabled=' + enabled + '&secret=dn17ORUwf36Znl', {\n" +
                "        method: 'POST'\n" +
                "    }).then(function(res) { return res.json(); })\n" +
                "      .then(function(result) {\n" +
                "          alert(result.code === 200 ? result.msg : result.msg);\n" +
                "          if (result.code === 200) loadRateLimitConfigs();\n" +
                "      });\n" +
                "}\n" +
                "\n" +
                "function deleteRateLimit(id) {\n" +
                "    if (!confirm('确认要删除该配置吗？删除后该IP将使用默认限流规则。')) return;\n" +
                "\n" +
                "    fetch('/api/monitor/rate-limit/delete?id=' + id + '&secret=dn17ORUwf36Znl', {\n" +
                "        method: 'POST'\n" +
                "    }).then(function(res) { return res.json(); })\n" +
                "      .then(function(result) {\n" +
                "          alert(result.code === 200 ? result.msg : result.msg);\n" +
                "          if (result.code === 200) loadRateLimitConfigs();\n" +
                "      });\n" +
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
                "var operationNameMap = {\n" +
                "    'RESET_2FA': '重置2FA',\n" +
                "    'DISABLE_2FA': '解除2FA',\n" +
                "    'UNLOCK_ACCOUNT': '解锁账户',\n" +
                "    'UNLOCK_IP': '解锁IP',\n" +
                "    'RATE_LIMIT_CREATE': '新增限流配置',\n" +
                "    'RATE_LIMIT_UPDATE': '修改限流配置',\n" +
                "    'RATE_LIMIT_DELETE': '删除限流配置',\n" +
                "    'RATE_LIMIT_TOGGLE': '启用/禁用限流配置',\n" +
                "    'RATE_LIMIT_UNBLOCK': '手动解封IP'\n" +
                "};\n" +
                "\n" +
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
                "            '<td><span class=\"status-badge ' + statusClass + '\"><span class=\"status-dot\"></span>' + statusText + '</span></td>' +\n" +
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
                "loadBlockedIps();\n" +
                "loadRateLimitConfigs();\n" +
                "loadLogs();\n" +
                "loadCharts();\n" +
                "setInterval(function(){ loadStats(); loadCharts(); }, 30000);\n" +
                "loadStats();\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }

    private String generateQueueManagementHtml() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=no\">\n" +
                "<title>Texcell LTM WEB 发送队列管理</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='22' fill='%231677ff'/%3E%3Ctext x='50' y='72' font-size='60' font-weight='bold' text-anchor='middle' fill='white' font-family='Arial'%3ET%3C/text%3E%3C/svg%3E\">\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:ital,wght@0,300;0,400;0,500;0,600;0,700;1,400&display=swap\" rel=\"stylesheet\">\n" +
                "<script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>\n" +
                "<link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.css\" />\n" +
                "<script src=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.js\"></script>\n" +
                "<style>\n" +
                ":root {\n" +
                "  --primary-color:#1677ff;\n" +
                "  --primary-hover:#3c8dff;\n" +
                "  --text-primary:#1e293b;\n" +
                "  --text-secondary:#475569;\n" +
                "  --text-muted:#8a97a8;\n" +
                "  --input-border:#d9e2ec;\n" +
                "  --bg-page:#f5f7fa;\n" +
                "  --bg-card:#ffffff;\n" +
                "  --border-color:#e8ecf1;\n" +
                "  --shadow-sm:0 1px 3px rgba(0,0,0,0.06);\n" +
                "  --shadow-hover:0 4px 12px rgba(22,119,255,0.1);\n" +
                "  --success-color:#52c41a;\n" +
                "  --danger-color:#ff4d4f;\n" +
                "  --warn-color:#faad14;\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:var(--bg-page);font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;min-height:100vh;}\n" +
                ".quick-btn{background:#f1f5f9;border:1px solid var(--border-color);color:var(--text-secondary);padding:6px 14px;border-radius:40px;cursor:pointer;font-size:12px;font-weight:500;transition:all 0.3s;font-family:inherit;}\n" +
                ".quick-btn:hover{border-color:var(--primary-color);color:var(--primary-color);transform:translateY(-1px);}\n" +
                ".quick-btn.active{background:var(--primary-color);color:#fff;border-color:var(--primary-color);}\n" +
                ".navbar{background:var(--bg-card);box-shadow:var(--shadow-sm);border-bottom:1px solid var(--border-color);position:sticky;top:0;z-index:100;}\n" +
                ".nav-container{max-width:2100px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;padding:0 32px;height:70px;}\n" +
                ".logo{display:flex;align-items:center;gap:12px;}\n" +
                ".logo-icon{width:40px;height:40px;background:linear-gradient(135deg,var(--primary-color) 0%,var(--primary-hover) 100%);border-radius:12px;display:flex;align-items:center;justify-content:center;}\n" +
                ".logo-icon svg{width:22px;height:22px;stroke:#fff;stroke-width:2;fill:none;}\n" +
                ".logo-text{font-size:18px;font-weight:700;color:var(--text-primary);letter-spacing:1px;}\n" +
                ".logo-sub{font-size:11px;color:var(--text-muted);margin-left:8px;}\n" +
                ".menu{display:flex;gap:12px;}\n" +
                ".menu a{color:var(--text-secondary);text-decoration:none;padding:8px 20px;border-radius:40px;transition:all 0.3s;font-weight:500;font-size:14px;}\n" +
                ".menu a:hover{background:#f1f5f9;color:var(--text-primary);}\n" +
                ".menu a.active{background:#e8f0fe;color:var(--primary-color);box-shadow:none;}\n" +
                ".user-info{display:flex;align-items:center;gap:20px;}\n" +
                ".user-name{color:var(--text-secondary);font-size:14px;}\n" +
                ".user-role{background:rgba(22,119,255,0.1);padding:4px 12px;border-radius:20px;font-size:12px;color:var(--primary-color);}\n" +
                ".logout-btn{background:#f1f5f9;border:1px solid var(--border-color);color:var(--text-secondary);padding:8px 20px;border-radius:40px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".page-wrap{max-width:2100px;margin:0 auto;padding:32px;}\n" +
                ".rank-card{background:var(--bg-card);border-radius:12px;padding:24px;margin-bottom:32px;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);transition:all 0.3s;}\n" +
                ".rank-card:hover{box-shadow:var(--shadow-hover);}\n" +
                ".rank-title{font-size:16px;font-weight:600;color:var(--text-primary);margin-bottom:20px;display:flex;align-items:center;gap:8px;}\n" +
                ".rank-title svg{width:18px;height:18px;stroke:var(--primary-color);stroke-width:2;fill:none;}\n" +
                ".rank-chart-container{height:300px;position:relative;}\n" +
                ".search-card{background:var(--bg-card);border-radius:12px;padding:24px;margin-bottom:32px;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);}\n" +
                ".search-row{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-end;}\n" +
                ".search-group{display:flex;flex-direction:column;gap:6px;}\n" +
                ".search-group label{font-size:12px;color:var(--text-secondary);font-weight:500;display:flex;align-items:center;gap:6px;}\n" +
                ".search-group label svg{width:14px;height:14px;stroke:var(--text-muted);stroke-width:2;fill:none;}\n" +
                ".search-group input,.search-group select{padding:10px 12px;border:1px solid var(--input-border);border-radius:10px;font-size:13px;min-width:180px;background:var(--bg-card);color:var(--text-primary);font-family:inherit;transition:all 0.3s;}\n" +
                ".search-group input:focus,.search-group select:focus{outline:none;border-color:var(--primary-color);box-shadow:0 0 0 3px rgba(22,119,255,0.12);}\n" +
                ".search-actions{display:flex;gap:10px;margin-left:auto;}\n" +
                ".search-btn{background:var(--primary-color);color:white;border:none;padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;display:inline-flex;align-items:center;gap:6px;}\n" +
                ".search-btn:hover{background:var(--primary-hover);box-shadow:0 8px 20px -6px rgba(22,119,255,0.45);}\n" +
                ".search-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".reset-btn{background:#f1f5f9;border:1px solid var(--border-color);padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;color:var(--text-secondary);display:inline-flex;align-items:center;gap:6px;}\n" +
                ".reset-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".reset-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".log-card{background:var(--bg-card);border-radius:12px;overflow:hidden;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);}\n" +
                ".table-wrapper{overflow-x:auto;}\n" +
                ".log-table{width:100%;border-collapse:collapse;min-width:1200px;}\n" +
                ".log-table th{background:#f8fafc;padding:14px 16px;text-align:left;font-size:13px;font-weight:600;color:var(--text-secondary);border-bottom:1px solid var(--border-color);white-space:nowrap;}\n" +
                ".log-table td{padding:14px 16px;font-size:13px;color:var(--text-primary);border-bottom:1px solid var(--border-color);}\n" +
                ".log-table tr:hover td{background:#f8fafc;}\n" +
                ".sms-content{max-width:350px;word-break:break-all;white-space:normal;line-height:1.5;color:var(--text-secondary);}\n" +
                ".badge-subaccount{background:#e8f0fe;color:var(--primary-color);padding:3px 10px;border-radius:20px;font-size:11px;display:inline-flex;align-items:center;gap:4px;font-weight:500;}\n" +
                ".badge-subaccount svg{width:12px;height:12px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".badge-delivered{color:var(--success-color);font-weight:600;}\n" +
                ".badge-failed{color:var(--danger-color);font-weight:600;}\n" +
                ".action-btn{padding:6px 14px;border-radius:40px;cursor:pointer;font-size:12px;transition:all 0.3s;border:none;font-weight:500;display:inline-flex;align-items:center;gap:4px;}\n" +
                ".action-btn svg{width:13px;height:13px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".view-btn{background:#e8f0fe;color:var(--primary-color);margin-right:6px;}\n" +
                ".view-btn:hover{background:var(--primary-color);color:white;transform:translateY(-1px);}\n" +
                ".export-btn{background:rgba(82,196,26,0.1);color:var(--success-color);margin-right:6px;}\n" +
                ".export-btn:hover{background:var(--success-color);color:white;transform:translateY(-1px);}\n" +
                ".delete-btn{background:rgba(255,77,79,0.1);color:var(--danger-color);}\n" +
                ".delete-btn:hover{background:var(--danger-color);color:white;transform:translateY(-1px);}\n" +
                ".pagination{display:flex;justify-content:center;align-items:center;gap:12px;padding:16px 20px;border-top:1px solid var(--border-color);background:#f8fafc;flex-wrap:wrap;}\n" +
                ".page-btn{padding:8px 16px;border:1px solid var(--border-color);background:var(--bg-card);color:var(--text-primary);border-radius:40px;cursor:pointer;font-size:13px;transition:all 0.3s;font-weight:500;}\n" +
                ".page-btn:hover:not(:disabled){border-color:var(--primary-color);color:var(--primary-color);transform:translateY(-1px);}\n" +
                ".page-btn:disabled{opacity:0.5;cursor:not-allowed;background:#f1f5f9;}\n" +
                ".page-info{font-size:13px;color:var(--text-muted);}\n" +
                ".footer{text-align:center;padding:32px 0;color:var(--text-muted);font-size:12px;border-top:1px solid var(--border-color);margin-top:32px;}\n" +
                ".empty{text-align:center;padding:48px;color:var(--text-muted);}\n" +
                "/* IP弹窗美化 */\n" +
                "#ipModal .modal-content{border-radius:16px;box-shadow:0 24px 60px -12px rgba(15,45,105,0.25);overflow:hidden;border:1px solid var(--border-color);}\n" +
                "#ipModal .modal-header{background:var(--bg-card);padding:18px 24px;border-bottom:1px solid var(--border-color);}\n" +
                "#ipModal .modal-header h3{font-size:17px;font-weight:600;color:var(--text-primary);display:flex;align-items:center;gap:8px;}\n" +
                "#ipModal .modal-header h3 svg{width:18px;height:18px;stroke:var(--primary-color);stroke-width:2;fill:none;}\n" +
                "#ipModal .modal-close{width:32px;height:32px;border-radius:50%;display:flex;align-items:center;justify-content:center;transition:all 0.3s;font-size:22px;background:none;border:none;cursor:pointer;color:var(--text-muted);line-height:1;}\n" +
                "#ipModal .modal-close:hover{background:rgba(0,0,0,0.06);color:var(--text-primary);}\n" +
                "#ipModal .modal-body{padding:20px 24px 12px 24px;}\n" +
                "#ipModal .modal-buttons{padding:12px 24px 20px 24px;border-top:1px solid var(--border-color);display:flex;justify-content:flex-end;}\n" +
                "#ipModal .cancel-btn{padding:10px 32px;border-radius:10px;font-weight:500;font-size:14px;transition:all 0.3s;background:#f1f5f9;border:1px solid var(--border-color);cursor:pointer;color:var(--text-secondary);}\n" +
                "#ipModal .cancel-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".ip-link{color:var(--primary-color) !important;cursor:pointer;text-decoration:underline;transition:all 0.3s;}\n" +
                ".ip-link:hover{color:var(--primary-hover) !important;text-decoration:none;}\n" +
                ".modal{position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(15,32,64,0.45);z-index:10000;display:flex;align-items:center;justify-content:center;backdrop-filter:blur(4px);-webkit-backdrop-filter:blur(4px);}\n" +
                ".modal-content{background:var(--bg-card);border-radius:16px;width:1000px;max-width:90%;max-height:85vh;display:flex;flex-direction:column;box-shadow:0 24px 60px -12px rgba(15,45,105,0.25);border:1px solid var(--border-color);}\n" +
                ".modal-header{display:flex;justify-content:space-between;align-items:center;padding:20px 24px;border-bottom:1px solid var(--border-color);}\n" +
                ".modal-header h3{color:var(--text-primary);margin:0;font-size:17px;font-weight:600;display:flex;align-items:center;gap:8px;}\n" +
                ".modal-header h3 svg{width:18px;height:18px;stroke:var(--primary-color);stroke-width:2;fill:none;}\n" +
                ".modal-close{background:none;border:none;font-size:26px;cursor:pointer;color:var(--text-muted);line-height:1;transition:color 0.2s;}\n" +
                ".modal-close:hover{color:var(--text-primary);}\n" +
                ".modal-body{padding:20px 24px;overflow-y:auto;flex:1;}\n" +
                ".detail-summary{background:#f8fafc;border-radius:10px;padding:14px 18px;margin-bottom:20px;display:flex;gap:24px;flex-wrap:wrap;border:1px solid var(--border-color);}\n" +
                ".detail-summary-item{display:flex;align-items:center;gap:8px;}\n" +
                ".detail-summary-label{font-size:12px;color:var(--text-muted);}\n" +
                ".detail-summary-value{font-size:16px;font-weight:700;color:var(--text-primary);}\n" +
                ".detail-search{margin-bottom:16px;display:flex;gap:10px;}\n" +
                ".detail-search input{flex:1;padding:10px 14px;border:1px solid var(--input-border);border-radius:10px;background:var(--bg-card);color:var(--text-primary);font-family:inherit;transition:border-color .2s,box-shadow .2s;}\n" +
                ".detail-search input:focus{outline:none;border-color:var(--primary-color);box-shadow:0 0 0 3px rgba(22,119,255,0.12);}\n" +
                ".detail-search button{padding:10px 22px;background:var(--primary-color);color:white;border:none;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;display:inline-flex;align-items:center;gap:6px;}\n" +
                ".detail-search button:hover{background:var(--primary-hover);box-shadow:0 8px 20px -6px rgba(22,119,255,0.45);}\n" +
                ".detail-search button svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".detail-table{width:100%;border-collapse:collapse;}\n" +
                ".detail-table th,.detail-table td{padding:12px;text-align:left;border-bottom:1px solid var(--border-color);font-size:13px;}\n" +
                ".detail-table th{background:#f8fafc;font-weight:600;color:var(--text-secondary);}\n" +
                ".detail-table td{color:var(--text-primary);}\n" +
                ".detail-table tr:hover td{background:#f8fafc;}\n" +
                ".detail-status-success{color:var(--success-color);font-weight:600;}\n" +
                ".detail-status-failed{color:var(--danger-color);font-weight:600;}\n" +
                ".detail-status-pending{color:var(--warn-color);font-weight:600;}\n" +
                ".detail-pagination{display:flex;justify-content:center;align-items:center;gap:12px;margin-top:20px;padding-top:16px;border-top:1px solid var(--border-color);flex-wrap:wrap;}\n" +
                "@media(max-width:768px){.search-actions{margin-left:0;width:100%;}.nav-container{padding:0 20px;}.menu a{padding:6px 14px;font-size:12px;}.rank-chart-container{height:250px;}.page-wrap{padding:20px;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"navbar\">\n" +
                "<div class=\"nav-container\">\n" +
                "<div class=\"logo\">\n" +
                "<div class=\"logo-icon\">\n" +
                "<svg viewBox=\"0 0 24 24\"><path d=\"M3 3v18h18\"/><path d=\"M18.7 8l-5.1 5.2-2.8-2.7L7 14.3\"/></svg>\n" +
                "</div>\n" +
                "<div class=\"logo-text\">Texcell LTM 监控管理系统</div>\n" +
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
                "<a href=\"/monitor/dr-auto-fix\">定时任务配置</a>\n" +
                "<a href=\"/monitor/feature-management\">功能模块管理</a>\n" +
                "<a href=\"/monitor/subtask-management\">子任务队列</a>\n" +
                "</div>\n" +
                "<div class=\"user-info\">\n" +
                "<span class=\"user-name\" id=\"currentUsername\">加载中...</span>\n" +
                "<span class=\"user-role\" id=\"currentUserRole\">加载中...</span>\n" +
                "<button class=\"logout-btn\" onclick=\"logout()\">退出</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"page-wrap\">\n" +
                "<div class=\"rank-card\">\n" +
                "<div class=\"rank-title\"><svg viewBox=\"0 0 24 24\"><path d=\"M6 9H4.5a2.5 2.5 0 0 1 0-5H6\"/><path d=\"M18 9h1.5a2.5 2.5 0 0 0 0-5H18\"/><path d=\"M4 22h16\"/><path d=\"M10 14.66V17c0 .55-.47.98-.97 1.21C7.85 18.75 7 20.24 7 22\"/><path d=\"M14 14.66V17c0 .55.47.98.97 1.21C16.15 18.75 17 20.24 17 22\"/><path d=\"M18 2H6v7a6 6 0 0 0 12 0V2z\"/></svg>近30分钟客户提交量排行 (Top 10)</div>\n" +
                "<div class=\"rank-chart-container\">\n" +
                "<canvas id=\"rankingChart\"></canvas>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"search-card\">\n" +
                "<div class=\"search-row\">\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><path d=\"M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2\"/><circle cx=\"12\" cy=\"7\" r=\"4\"/></svg>客户账号</label><input type=\"text\" id=\"searchSubAccount\" placeholder=\"模糊查询\"></div>\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"3\" width=\"18\" height=\"18\" rx=\"2\"/><path d=\"M9 9h6v6H9z\"/></svg>任务ID</label><input type=\"text\" id=\"searchTaskId\" placeholder=\"模糊查询\"></div>\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><line x1=\"2\" y1=\"12\" x2=\"22\" y2=\"12\"/><path d=\"M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z\"/></svg>提交IP</label><input type=\"text\" id=\"searchTaskIp\" placeholder=\"模糊查询\"></div>\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"4\" width=\"18\" height=\"18\" rx=\"2\" ry=\"2\"/><line x1=\"16\" y1=\"2\" x2=\"16\" y2=\"6\"/><line x1=\"8\" y1=\"2\" x2=\"8\" y2=\"6\"/><line x1=\"3\" y1=\"10\" x2=\"21\" y2=\"10\"/></svg>开始时间</label><input type=\"datetime-local\" id=\"searchStartTime\" style=\"min-width:200px;\"></div>\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"4\" width=\"18\" height=\"18\" rx=\"2\" ry=\"2\"/><line x1=\"16\" y1=\"2\" x2=\"16\" y2=\"6\"/><line x1=\"8\" y1=\"2\" x2=\"8\" y2=\"6\"/><line x1=\"3\" y1=\"10\" x2=\"21\" y2=\"10\"/></svg>结束时间</label><input type=\"datetime-local\" id=\"searchEndTime\" style=\"min-width:200px;\"></div>\n" +
                "<div class=\"search-actions\"><button class=\"search-btn\" onclick=\"searchTasks()\"><svg viewBox=\"0 0 24 24\"><circle cx=\"11\" cy=\"11\" r=\"8\"/><line x1=\"21\" y1=\"21\" x2=\"16.65\" y2=\"16.65\"/></svg>查询</button><button class=\"reset-btn\" onclick=\"resetSearch()\"><svg viewBox=\"0 0 24 24\"><polyline points=\"23 4 23 10 17 10\"/><polyline points=\"1 20 1 14 7 14\"/><path d=\"M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15\"/></svg>重置</button></div>\n" +
                "</div>\n" +
                "<div class=\"quick-range\" style=\"display:flex;flex-wrap:wrap;gap:8px;margin-top:16px;\">\n" +
                "  <button type=\"button\" class=\"quick-btn\" onclick=\"setQuickRange(1, this)\">最近1小时</button>\n" +
                "  <button type=\"button\" class=\"quick-btn\" onclick=\"setQuickRange(6, this)\">最近6小时</button>\n" +
                "  <button type=\"button\" class=\"quick-btn\" onclick=\"setQuickRange(12, this)\">最近12小时</button>\n" +
                "  <button type=\"button\" class=\"quick-btn\" onclick=\"setQuickRange('today', this)\">当天</button>\n" +
                "  <button type=\"button\" class=\"quick-btn\" onclick=\"setQuickRange(72, this)\">最近三天</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"log-card\">\n" +
                "<div class=\"table-wrapper\">\n" +
                "<table class=\"log-table\">\n" +
                "<thead><tr><th>任务ID</th><th>客户账号</th><th>提交IP</th><th>任务名称</th><th>短信内容</th><th>创建时间</th><th>计划时间</th><th>总数</th><th>已提交</th><th>成功</th><th>失败</th><th>操作</th></tr></thead>\n" +
                "<tbody id=\"taskBody\"><tr><td colspan=\"12\" class=\"empty\">加载中...</td></tr></tbody>\n" +
                "</table>\n" +
                "</div>\n" +
                "<div class=\"pagination\" id=\"pagination\"></div>\n" +
                "</div>\n" +
                "<!-- IP地理位置弹窗 -->\n" +
                "<div id=\"ipModal\" class=\"modal\" style=\"display:none;\">\n" +
                "    <div class=\"modal-content\" style=\"max-width:450px;\">\n" +
                "        <div class=\"modal-header\">\n" +
                "            <h3><svg viewBox=\"0 0 24 24\"><path d=\"M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z\"/><circle cx=\"12\" cy=\"10\" r=\"3\"/></svg>IP地理位置</h3>\n" +
                "            <button class=\"modal-close\" onclick=\"closeIpModal()\">&times;</button>\n" +
                "        </div>\n" +
                "        <div class=\"modal-body\" id=\"ipModalBody\">\n" +
                "            <div style=\"text-align:center;padding:20px;\">\n" +
                "                <div id=\"ipInfoContent\">加载中...</div>\n" +
                "            </div>\n" +
                "<div id=\"ipMap\" style=\"height:300px;border-radius:10px;border:1px solid #e8ecf1;display:none;\"></div>\n"+
                "        </div>\n" +
                "        <div class=\"modal-buttons\">\n" +
                "            <button class=\"cancel-btn\" onclick=\"closeIpModal()\">关闭</button>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "</div>\n" +
                "\n" +
                "<div class=\"footer\">客户发送队列管理 | 仅显示最近3天数据 | 双击任务ID可查看详情</div>\n" +
                "</div>\n" +
                "<div id=\"detailModal\" class=\"modal\" style=\"display:none;\">\n" +
                "<div class=\"modal-content\">\n" +
                "<div class=\"modal-header\"><h3 id=\"modalTitle\"><svg viewBox=\"0 0 24 24\"><path d=\"M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z\"/><polyline points=\"14 2 14 8 20 8\"/><line x1=\"16\" y1=\"13\" x2=\"8\" y2=\"13\"/><line x1=\"16\" y1=\"17\" x2=\"8\" y2=\"17\"/></svg>任务详情</h3><button class=\"modal-close\" onclick=\"closeDetailModal()\">&times;</button></div>\n" +
                "<div class=\"modal-body\">\n" +
                "<div class=\"detail-summary\" id=\"detailSummary\"></div>\n" +
                "<div class=\"detail-search\"><input type=\"text\" id=\"detailCallee\" placeholder=\"按号码筛选\"><button onclick=\"loadTaskDetail()\"><svg viewBox=\"0 0 24 24\"><circle cx=\"11\" cy=\"11\" r=\"8\"/><line x1=\"21\" y1=\"21\" x2=\"16.65\" y2=\"16.65\"/></svg>查询</button></div>\n" +
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
                "function escapeHtml(s){if(!s)return'';return String(s).replace(/[&<>]/g,function(m){if(m==='&')return'&amp;';if(m==='<')return'&lt;';if(m==='>')return'&gt;';return m;});}\n" +
                "function decodeSmsContent(str){if(!str)return'';try{var decoded=atob(str);var bytes=new Uint8Array(decoded.split('').map(function(c){return c.charCodeAt(0);}));return new TextDecoder('utf-8').decode(bytes);}catch(e){return str;}}\n" +
                "function logout(){fetch('/monitor/logout').then(function(){location.href='/monitor/login';});}\n" +
                "function getCurrentUserInfo() {\n" +
                "    fetch('/api/monitor/current-user?secret=dn17ORUwf36Znl')\n" +
                "        .then(function(res) { return res.json(); })\n" +
                "        .then(function(data) {\n" +
                "            if (data.code === 200) {\n" +
                "                document.getElementById('currentUsername').innerText = data.data.username;\n" +
                "                var roleSpan = document.getElementById('currentUserRole');\n" +
                "                var roleText = data.data.role === 'ADMIN' ? '管理员' : 'NOC';\n" +
                "                roleSpan.innerText = roleText;\n" +
                "            }\n" +
                "        }).catch(function(err) { console.error('获取用户信息失败:', err); });\n" +
                "}\n" +
                "getCurrentUserInfo();\n" +
                "function loadRanking(){fetch('/api/monitor/queue/customer-ranking?secret=dn17ORUwf36Znl').then(function(res){return res.json();}).then(function(data){var ctx=document.getElementById('rankingChart').getContext('2d');if(rankingChart)rankingChart.destroy();if(data.code===200&&data.data&&data.data.length){var list=data.data;var labels=list.map(function(i){return i.subAccount||i.username||'-';});var counts=list.map(function(i){return i.totalCount||0;});rankingChart=new Chart(ctx,{type:'bar',data:{labels:labels,datasets:[{label:'提交数量(条)',data:counts,backgroundColor:'#1677ff',borderRadius:8}]},options:{responsive:true,maintainAspectRatio:true}});}else{rankingChart=new Chart(ctx,{type:'bar',data:{labels:['暂无数据'],datasets:[{label:'提交数量',data:[0]}]},options:{responsive:true}});}}).catch(function(){var ctx=document.getElementById('rankingChart').getContext('2d');if(rankingChart)rankingChart.destroy();rankingChart=new Chart(ctx,{type:'bar',data:{labels:['加载失败'],datasets:[{label:'提交数量',data:[0]}]},options:{responsive:true}});});}\n" +
                "function formatNumber(num){if(!num)return'0';return num.toString().replace(/\\B(?=(\\d{3})+(?!\\d))/g,',');}\n" +
                "function searchTasks(){page=1;loadTasks();}\n" +
                "function resetSearch(){\n" +
                "    document.getElementById('searchSubAccount').value='';\n" +
                "    document.getElementById('searchTaskId').value='';\n" +
                "    document.getElementById('searchTaskIp').value='';\n" +
                "    document.getElementById('searchStartTime').value='';\n" +
                "    document.getElementById('searchEndTime').value='';\n" +
                "    document.querySelectorAll('.quick-btn').forEach(function(b){ b.classList.remove('active'); });\n" +
                "    page=1;\n" +
                "    loadTasks();\n" +
                "}\n" +
                "function loadTasks(){\n" +
                "    var subAccount = document.getElementById('searchSubAccount').value;\n" +
                "    var taskId = document.getElementById('searchTaskId').value;\n" +
                "    var taskIp = document.getElementById('searchTaskIp').value;\n" +
                "    var st = document.getElementById('searchStartTime').value;\n" +
                "    var et = document.getElementById('searchEndTime').value;\n" +
                "    var url = '/api/monitor/queue/tasks?secret=dn17ORUwf36Znl&pageNum=' + page + '&pageSize=' + size;\n" +
                "    if (subAccount) url += '&subAccount=' + encodeURIComponent(subAccount);\n" +
                "    if (taskId) url += '&taskId=' + encodeURIComponent(taskId);\n" +
                "    if (taskIp) url += '&taskIp=' + encodeURIComponent(taskIp);\n" +
                "    if (st) url += '&startTime=' + encodeURIComponent(st.replace('T', ' ') + ':00');\n" +
                "    if (et) url += '&endTime=' + encodeURIComponent(et.replace('T', ' ') + ':00');\n" +
                "    fetch(url).then(function(res){ return res.json(); }).then(function(data){\n" +
                "        if (data.code === 200) {\n" +
                "            var records = data.data && data.data.records ? data.data.records : [];\n" +
                "            totalPages = Math.ceil(((data.data && data.data.total) || 0) / size);\n" +
                "            renderTable(records);\n" +
                "            renderPagination();\n" +
                "        } else {\n" +
                "            document.getElementById('taskBody').innerHTML = '<tr><td colspan=\"12\" class=\"empty\">加载失败:' + (data.msg || '错误') + '</td></tr>';\n" +
                "        }\n" +
                "    }).catch(function(){\n" +
                "        document.getElementById('taskBody').innerHTML = '<tr><td colspan=\"12\" class=\"empty\">加载失败:网络错误</td></tr>';\n" +
                "    });\n" +
                "}\n" +
                "function renderTable(list){var tbody=document.getElementById('taskBody');if(!list||!list.length){tbody.innerHTML='<tr><td colspan=\"12\" class=\"empty\">暂无数据</td></tr>';return;}var html='';for(var i=0;i<list.length;i++){var t=list[i];var smsContent=decodeSmsContent(t.shortMessage||'');var taskIdAttr=escapeHtml(String(t.taskId||''));var scheduleStatus = t.scheduleStatus !== undefined ? t.scheduleStatus : 0;var taskIp = t.taskIp || '-';\n" +
                "html+='<tr class=\"task-row\" data-task-id=\"'+taskIdAttr+'\" style=\"cursor:pointer;\">';\n" +
                "html+='<td style=\"font-family:monospace;font-size:12px;color:#475569;\" title=\"'+escapeHtml(t.taskId||'')+'\">'+escapeHtml((t.taskId||'').substring(0,20))+'...</td>';\n" +
                "html+='<td><span class=\"badge-subaccount\"><svg viewBox=\"0 0 24 24\"><path d=\"M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2\"/><circle cx=\"12\" cy=\"7\" r=\"4\"/></svg>'+escapeHtml(t.subAccount||'')+'</span></td>';\n" +
                "if (taskIp !== '-' && taskIp !== '') {\n" +
                "    html+='<td><span class=\"ip-link\" onclick=\"showIpLocation(\\''+escapeHtml(taskIp)+'\\')\">'+escapeHtml(taskIp)+'</span></td>';\n" +
                "} else {\n" +
                "    html+='<td style=\"color:#94a3b8;\">-</td>';\n" +
                "}\n" +
                "html+='<td title=\"'+escapeHtml(t.taskName||'')+'\">'+escapeHtml((t.taskName||'').substring(0,30))+'</td>';\n" +
                "html+='<td class=\"sms-content\">'+escapeHtml(smsContent).replace(/\\\\n/g,'<br>')+'</td>';\n" +
                "html+='<td style=\"white-space:nowrap;\">'+escapeHtml(formatDateTime(t.taskTime))+'</td>';\n" +
                "html+='<td style=\"white-space:nowrap;\">'+escapeHtml(formatDateTime(t.scheduleSendTime))+'</td>';\n" +
                "html+='<td>'+formatNumber(t.totalCount||0)+'</td>';\n" +
                "html+='<td>'+formatNumber(t.submitSuccCount||0)+'</td>';\n" +
                "html+='<td class=\"badge-delivered\">'+formatNumber(t.drSuccCount||0)+'</td>';\n" +
                "html+='<td class=\"badge-failed\">'+formatNumber(t.drFailCount||0)+'</td>';\n" +
                "html+='<td style=\"white-space:nowrap;\">';\n" +
                "html+='<button class=\"action-btn view-btn\" onclick=\"showTaskDetail(\\''+taskIdAttr+'\\')\"><svg viewBox=\"0 0 24 24\"><path d=\"M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7z\"/><circle cx=\"12\" cy=\"12\" r=\"3\"/></svg>详情</button>';\n" +
                "html+='<button class=\"action-btn export-btn\" onclick=\"exportTask(\\''+taskIdAttr+'\\')\"><svg viewBox=\"0 0 24 24\"><path d=\"M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4\"/><polyline points=\"7 10 12 15 17 10\"/><line x1=\"12\" y1=\"15\" x2=\"12\" y2=\"3\"/></svg>导出</button>';\n" +
                "if (scheduleStatus === 0) {\n" +
                "    html+='<button class=\"action-btn delete-btn\" onclick=\"deleteTask(\\''+taskIdAttr+'\\')\"><svg viewBox=\"0 0 24 24\"><polyline points=\"3 6 5 6 21 6\"/><path d=\"M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2\"/></svg>删除</button>';\n" +
                "}\n" +
                "html+='</td></tr>';}tbody.innerHTML=html;}\n" +
                "function escapeHtml(s){if(!s)return'';return String(s).replace(/[&<>]/g,function(m){if(m==='&')return'&amp;';if(m==='<')return'&lt;';if(m==='>')return'&gt;';return m;});}\n" +
                "function formatDateTime(str){\n" +
                "    if(!str) return '';\n" +
                "    return String(str).replace('T', ' ').substring(0, 19);\n" +
                "}\n" +
                "function showIpLocation(ip) {\n" +
                "    if (!ip || ip === '-') return;\n" +
                "    \n" +
                "    var modal = document.getElementById('ipModal');\n" +
                "    var content = document.getElementById('ipInfoContent');\n" +
                "    var mapContainer = document.getElementById('ipMap');\n" +
                "    \n" +
                "    modal.style.display = 'flex';\n" +
                "    content.innerHTML = '<div style=\"text-align:center;padding:30px 0;color:#8a97a8;\">查询中...</div>';\n" +
                "    mapContainer.style.display = 'none';\n" +
                "    \n" +
                "    fetch('/api/monitor/queue/ip-location?secret=dn17ORUwf36Znl&ip=' + encodeURIComponent(ip))\n" +
                "        .then(function(res) { return res.json(); })\n" +
                "        .then(function(data) {\n" +
                "            if (data.code === 200) {\n" +
                "                var info = data.data;\n" +
                "                var html = '<div style=\"background:#f8fafc;border-radius:12px;padding:20px;margin-bottom:12px;border:1px solid #e8ecf1;\">';\n" +
                "                html += '<div style=\"display:flex;align-items:center;gap:16px;margin-bottom:16px;padding-bottom:16px;border-bottom:2px dashed #e8ecf1;\">';\n" +
                "                html += '<div style=\"width:52px;height:52px;background:linear-gradient(135deg,#1677ff 0%,#3c8dff 100%);border-radius:50%;display:flex;align-items:center;justify-content:center;flex-shrink:0;box-shadow:0 6px 16px -4px rgba(22,119,255,0.4);\"><svg width=\"24\" height=\"24\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"white\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><line x1=\"2\" y1=\"12\" x2=\"22\" y2=\"12\"/><path d=\"M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z\"/></svg></div>';\n" +
                "                html += '<div><div style=\"font-size:20px;font-weight:700;color:#1e293b;\">' + escapeHtml(ip) + '</div>';\n" +
                "                html += '<div style=\"font-size:13px;color:#8a97a8;margin-top:4px;\">' + escapeHtml(info.location || '未知位置') + '</div></div>';\n" +
                "                html += '</div>';\n" +
                "                html += '<div style=\"display:grid;grid-template-columns:1fr 1fr;gap:8px 24px;\">';\n" +
                "                html += '<div style=\"display:flex;align-items:center;gap:8px;padding:8px 12px;background:#fff;border-radius:8px;border:1px solid #e8ecf1;\"><span style=\"font-size:12px;color:#8a97a8;\">国家</span><span style=\"font-weight:600;color:#1e293b;margin-left:auto;font-size:13px;\">' + escapeHtml(info.country || '-') + '</span></div>';\n" +
                "                html += '<div style=\"display:flex;align-items:center;gap:8px;padding:8px 12px;background:#fff;border-radius:8px;border:1px solid #e8ecf1;\"><span style=\"font-size:12px;color:#8a97a8;\">省份</span><span style=\"font-weight:600;color:#1e293b;margin-left:auto;font-size:13px;\">' + escapeHtml(info.region || '-') + '</span></div>';\n" +
                "                html += '<div style=\"display:flex;align-items:center;gap:8px;padding:8px 12px;background:#fff;border-radius:8px;border:1px solid #e8ecf1;\"><span style=\"font-size:12px;color:#8a97a8;\">城市</span><span style=\"font-weight:600;color:#1e293b;margin-left:auto;font-size:13px;\">' + escapeHtml(info.city || '-') + '</span></div>';\n" +
                "                html += '<div style=\"display:flex;align-items:center;gap:8px;padding:8px 12px;background:#fff;border-radius:8px;border:1px solid #e8ecf1;\"><span style=\"font-size:12px;color:#8a97a8;\">运营商</span><span style=\"font-weight:600;color:#1e293b;margin-left:auto;font-size:13px;\">' + escapeHtml(info.isp || '-') + '</span></div>';\n" +
                "                html += '</div></div>';\n" +
                "                content.innerHTML = html;\n" +
                "                \n" +
                "                if (info.lat && info.lon) {\n" +
                "                    mapContainer.style.display = 'block';\n" +
                "                    setTimeout(function() {\n" +
                "                        if (window.ipMapInstance) {\n" +
                "                            window.ipMapInstance.remove();\n" +
                "                        }\n" +
                "                        var map = L.map('ipMap').setView([info.lat, info.lon], 12);\n" +
                "                        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {\n" +
                "                            attribution: '© OpenStreetMap'\n" +
                "                        }).addTo(map);\n" +
                "                        var marker = L.marker([info.lat, info.lon], {\n" +
                "                            icon: L.divIcon({\n" +
                "                                className: 'custom-marker',\n" +
                "                                html: '<div style=\"background:linear-gradient(135deg,#1677ff 0%,#3c8dff 100%);width:32px;height:32px;border-radius:50%;display:flex;align-items:center;justify-content:center;box-shadow:0 4px 12px rgba(22,119,255,0.4);border:3px solid white;\"><svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"white\" stroke-width=\"2.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z\"/><circle cx=\"12\" cy=\"10\" r=\"3\"/></svg></div>',\n" +
                "                                iconSize: [32, 32],\n" +
                "                                iconAnchor: [16, 32]\n" +
                "                            })\n" +
                "                        }).addTo(map);\n" +
                "                        marker.bindPopup('<div style=\"font-weight:600;color:#1e293b;\">' + escapeHtml(ip) + '</div><div style=\"color:#64748b;font-size:13px;\">' + escapeHtml(info.city || '') + ', ' + escapeHtml(info.country || '') + '</div>');\n" +
                "                        window.ipMapInstance = map;\n" +
                "                        setTimeout(function() { map.invalidateSize(); }, 300);\n" +
                "                    }, 300);\n" +
                "                }\n" +
                "            } else {\n" +
                "                content.innerHTML = '<div style=\"text-align:center;padding:30px 0;color:#ff4d4f;\">' + (data.msg || '查询失败') + '</div>';\n" +
                "            }\n" +
                "        })\n" +
                "        .catch(function() {\n" +
                "            content.innerHTML = '<div style=\"text-align:center;padding:30px 0;color:#ff4d4f;\">网络错误，请重试</div>';\n" +
                "        });\n" +
                "}\n" +
                "function closeIpModal() {\n" +
                "    document.getElementById('ipModal').style.display = 'none';\n" +
                "    if (window.ipMapInstance) {\n" +
                "        window.ipMapInstance.remove();\n" +
                "        window.ipMapInstance = null;\n" +
                "    }\n" +
                "}\n" +
                "\n" +
                "document.addEventListener('click', function(e) {\n" +
                "    var modal = document.getElementById('ipModal');\n" +
                "    if (e.target === modal) {\n" +
                "        closeIpModal();\n" +
                "    }\n" +
                "});\n" +
                "\n" +
                "function renderPagination(){var div=document.getElementById('pagination');if(!div||totalPages<=1){div.innerHTML='';return;}var html='<button class=\"page-btn\" onclick=\"goPage(1)\"'+(page===1?'disabled':'')+'>首页</button><button class=\"page-btn\" onclick=\"goPage('+(page-1)+')\"'+(page===1?'disabled':'')+'>上一页</button><span class=\"page-info\">第 '+page+' / '+totalPages+' 页</span><button class=\"page-btn\" onclick=\"goPage('+(page+1)+')\"'+(page===totalPages?'disabled':'')+'>下一页</button><button class=\"page-btn\" onclick=\"goPage('+totalPages+')\"'+(page===totalPages?'disabled':'')+'>尾页</button>';div.innerHTML=html;}\n" +
                "function goPage(p){page=p;loadTasks();}\n" +
                "function pad(n){ return n < 10 ? '0' + n : '' + n; }\n" +
                "\n" +
                "function formatDateTimeLocal(date){\n" +
                "    return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate())\n" +
                "        + 'T' + pad(date.getHours()) + ':' + pad(date.getMinutes());\n" +
                "}\n" +
                "\n" +
                "function setQuickRange(hours, btn){\n" +
                "    var end = new Date();\n" +
                "    var start = new Date();\n" +
                "    if (hours === 'today') {\n" +
                "        start.setHours(0, 0, 0, 0);\n" +
                "    } else if (hours === 'yesterday') {\n" +
                "        start.setDate(start.getDate() - 1);\n" +
                "        start.setHours(0, 0, 0, 0);\n" +
                "        end.setDate(end.getDate() - 1);\n" +
                "        end.setHours(23, 59, 59, 0);\n" +
                "    } else {\n" +
                "        start.setTime(end.getTime() - hours * 60 * 60 * 1000);\n" +
                "    }\n" +
                "    document.getElementById('searchStartTime').value = formatDateTimeLocal(start);\n" +
                "    document.getElementById('searchEndTime').value = formatDateTimeLocal(end);\n" +
                "    document.querySelectorAll('.quick-btn').forEach(function(b){ b.classList.remove('active'); });\n" +
                "    if (btn) { btn.classList.add('active'); }\n" +
                "    page = 1;\n" +
                "    loadTasks();\n" +
                "}\n" +
                "function showTaskDetail(taskId){currentTaskId=taskId;detailPage=1;document.getElementById('modalTitle').innerHTML='<svg viewBox=\"0 0 24 24\"><path d=\"M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z\"/><polyline points=\"14 2 14 8 20 8\"/><line x1=\"16\" y1=\"13\" x2=\"8\" y2=\"13\"/><line x1=\"16\" y1=\"17\" x2=\"8\" y2=\"17\"/></svg>任务详情 - '+taskId;document.getElementById('detailCallee').value='';document.getElementById('detailModal').style.display='flex';loadTaskDetail();}\n" +
                "function loadTaskDetail(){var callee=document.getElementById('detailCallee').value;var url='/api/monitor/queue/task/detail?secret=dn17ORUwf36Znl&taskId='+encodeURIComponent(currentTaskId)+'&pageNum='+detailPage+'&pageSize='+detailSize;if(callee)url+='&callee='+encodeURIComponent(callee);fetch(url).then(function(res){return res.json();}).then(function(data){if(data.code===200){var records=data.data?.records||[];var total=data.data?.total||0;detailTotalPages=Math.ceil(total/detailSize);renderDetailTable(records);renderDetailPagination();}else{document.getElementById('detailBody').innerHTML='<tr><td colspan=\"7\" style=\"text-align:center;\">加载失败:'+(data.msg||'错误')+'</td></tr>';}});}\n" +
                "function renderDetailTable(list){var tbody=document.getElementById('detailBody');if(!list||!list.length){tbody.innerHTML='<tr><td colspan=\"7\" style=\"text-align:center;\">暂无数据</td></tr>';return;}var html='';for(var i=0;i<list.length;i++){var d=list[i];var statusClass=(d.recvDRStatus==='DELIVRD')?'detail-status-success':((d.recvDRStatus==='UNDELIV'||d.recvDRStatus==='REJECTD'||d.recvDRStatus==='UNKNOWN')?'detail-status-failed':'detail-status-pending');var statusText=(d.recvDRStatus==='DELIVRD')?'成功':((d.recvDRStatus==='UNDELIV'||d.recvDRStatus==='REJECTD'||d.recvDRStatus==='UNKNOWN')?'失败':'等待');html+='<tr>';\n" +
                "html+='<td style=\"font-family:monospace;font-size:12px;color:#475569;\" title=\"'+escapeHtml(d.submitMsgId||'')+'\">'+escapeHtml((d.submitMsgId||'').substring(0,20))+'...</td>';\n" +
                "html+='<td>'+escapeHtml(d.caller||'')+'</td>';\n" +
                "html+='<td>'+escapeHtml(d.callee||'')+'</td>';\n" +
                "html+='<td style=\"white-space:nowrap;\">'+escapeHtml(d.recvDRTime||'-')+'</td>';\n" +
                "html+='<td class=\"'+statusClass+'\">'+statusText+'</td>';\n" +
                "html+='<td>'+escapeHtml(d.recvDRError||'-')+'</td>';\n" +
                "html+='<td>'+(d.pkNumber||'')+'/'+(d.pkTotal||'')+'</td></tr>';}\n" +
                "tbody.innerHTML=html;}\n" +
                "function renderDetailPagination(){var div=document.getElementById('detailPagination');if(!div||detailTotalPages<=1){div.innerHTML='';return;}var html='<button class=\"page-btn\" onclick=\"goDetailPage(1)\"'+(detailPage===1?'disabled':'')+'>首页</button><button class=\"page-btn\" onclick=\"goDetailPage('+(detailPage-1)+')\"'+(detailPage===1?'disabled':'')+'>上一页</button><span class=\"page-info\">第 '+detailPage+' / '+detailTotalPages+' 页</span><button class=\"page-btn\" onclick=\"goDetailPage('+(detailPage+1)+')\"'+(detailPage===detailTotalPages?'disabled':'')+'>下一页</button><button class=\"page-btn\" onclick=\"goDetailPage('+detailTotalPages+')\"'+(detailPage===detailTotalPages?'disabled':'')+'>尾页</button>';div.innerHTML=html;}\n" +
                "function goDetailPage(p){detailPage=p;loadTaskDetail();}\n" +
                "function closeDetailModal(){document.getElementById('detailModal').style.display='none';currentTaskId='';detailPage=1;}\n" +
                "function exportTask(taskId){if(!taskId){alert('任务ID不能为空');return;}window.open('/api/monitor/queue/task/export?taskId='+encodeURIComponent(taskId)+'&secret=dn17ORUwf36Znl','_blank');}\n" +
                "function deleteTask(taskId){if(!taskId){alert('任务ID不能为空');return;}if(!confirm('确定要取消该任务吗？\\n\\n注意：取消后，该任务状态会标记为已取消，不会再进行发送！')){return;}fetch('/api/monitor/queue/task/delete?taskId='+encodeURIComponent(taskId)+'&secret=dn17ORUwf36Znl',{method:'DELETE'}).then(function(res){return res.json();}).then(function(data){if(data.code===200){alert((data.msg||'删除成功'));loadTasks();}else{alert('删除失败: '+(data.msg||'未知错误'));}}).catch(function(err){alert('网络错误: '+err.message);});}\n" +
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
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=no\">\n" +
                "<title>Texcell LTM WEB 队列DR监控</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='22' fill='%231677ff'/%3E%3Ctext x='50' y='72' font-size='60' font-weight='bold' text-anchor='middle' fill='white' font-family='Arial'%3ET%3C/text%3E%3C/svg%3E\">\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:ital,wght@0,300;0,400;0,500;0,600;0,700;1,400&display=swap\" rel=\"stylesheet\">\n" +
                "<style>\n" +
                ":root {\n" +
                "  --primary-color:#1677ff;\n" +
                "  --primary-hover:#3c8dff;\n" +
                "  --text-primary:#1e293b;\n" +
                "  --text-secondary:#475569;\n" +
                "  --text-muted:#8a97a8;\n" +
                "  --input-border:#d9e2ec;\n" +
                "  --bg-page:#f5f7fa;\n" +
                "  --bg-card:#ffffff;\n" +
                "  --border-color:#e8ecf1;\n" +
                "  --shadow-sm:0 1px 3px rgba(0,0,0,0.06);\n" +
                "  --shadow-hover:0 4px 12px rgba(22,119,255,0.1);\n" +
                "  --success-color:#52c41a;\n" +
                "  --danger-color:#ff4d4f;\n" +
                "  --warn-color:#faad14;\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:var(--bg-page);font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;}\n" +
                ".navbar{background:var(--bg-card);box-shadow:var(--shadow-sm);border-bottom:1px solid var(--border-color);position:sticky;top:0;z-index:100;}\n" +
                ".nav-container{max-width:2100px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;padding:0 32px;height:70px;}\n" +
                ".logo{display:flex;align-items:center;gap:12px;}\n" +
                ".logo-icon{width:40px;height:40px;background:linear-gradient(135deg,var(--primary-color) 0%,var(--primary-hover) 100%);border-radius:12px;display:flex;align-items:center;justify-content:center;}\n" +
                ".logo-icon svg{width:22px;height:22px;stroke:#fff;stroke-width:2;fill:none;}\n" +
                ".logo-text{font-size:18px;font-weight:700;color:var(--text-primary);letter-spacing:1px;}\n" +
                ".logo-sub{font-size:11px;color:var(--text-muted);margin-left:8px;}\n" +
                ".menu{display:flex;gap:12px;}\n" +
                ".menu a{color:var(--text-secondary);text-decoration:none;padding:8px 20px;border-radius:40px;transition:all 0.3s;font-weight:500;font-size:14px;}\n" +
                ".menu a:hover{background:#f1f5f9;color:var(--text-primary);}\n" +
                ".menu a.active{background:#e8f0fe;color:var(--primary-color);box-shadow:none;}\n" +
                ".user-info{display:flex;align-items:center;gap:20px;}\n" +
                ".user-name{color:var(--text-secondary);font-size:14px;}\n" +
                ".user-role{background:rgba(22,119,255,0.1);padding:4px 12px;border-radius:20px;font-size:12px;color:var(--primary-color);}\n" +
                ".logout-btn{background:#f1f5f9;border:1px solid var(--border-color);color:var(--text-secondary);padding:8px 20px;border-radius:40px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".page-wrap{max-width:2100px;margin:0 auto;padding:32px;}\n" +
                ".page-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:28px;flex-wrap:wrap;gap:16px;}\n" +
                ".page-title{font-size:22px;font-weight:700;color:var(--text-primary);display:flex;align-items:center;gap:12px;}\n" +
                ".page-title svg{width:24px;height:24px;stroke:var(--primary-color);stroke-width:2;fill:none;}\n" +
                ".page-title .badge{background:var(--danger-color);color:white;font-size:12px;padding:3px 14px;border-radius:20px;font-weight:500;display:inline-flex;align-items:center;gap:4px;}\n" +
                ".page-title .badge.warning{background:var(--warn-color);}\n" +
                ".stats-grid{display:grid;grid-template-columns:repeat(5,1fr);gap:20px;margin-bottom:32px;}\n" +
                ".stat-card{background:var(--bg-card);border-radius:12px;padding:24px 20px;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);transition:all 0.3s;}\n" +
                ".stat-card:hover{transform:translateY(-2px);box-shadow:var(--shadow-hover);border-color:var(--primary-color);}\n" +
                ".stat-number{font-size:32px;font-weight:800;color:var(--primary-color);line-height:1;}\n" +
                ".stat-label{font-size:13px;color:var(--text-secondary);margin-top:8px;font-weight:500;}\n" +
                ".search-card{background:var(--bg-card);border-radius:12px;padding:24px;margin-bottom:32px;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);}\n" +
                ".search-row{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-end;}\n" +
                ".search-group{display:flex;flex-direction:column;gap:6px;}\n" +
                ".search-group label{font-size:12px;color:var(--text-secondary);font-weight:500;display:flex;align-items:center;gap:6px;}\n" +
                ".search-group label svg{width:14px;height:14px;stroke:var(--text-muted);stroke-width:2;fill:none;}\n" +
                ".search-group input,.search-group select{padding:10px 12px;border:1px solid var(--input-border);border-radius:10px;font-size:13px;min-width:170px;transition:all 0.3s;background:var(--bg-card);color:var(--text-primary);font-family:inherit;}\n" +
                ".search-group input:focus,.search-group select:focus{outline:none;border-color:var(--primary-color);box-shadow:0 0 0 3px rgba(22,119,255,0.12);}\n" +
                ".search-actions{display:flex;gap:10px;margin-left:auto;align-items:center;flex-wrap:wrap;}\n" +
                ".search-btn{background:var(--primary-color);color:white;border:none;padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;display:inline-flex;align-items:center;gap:6px;}\n" +
                ".search-btn:hover{background:var(--primary-hover);box-shadow:0 8px 20px -6px rgba(22,119,255,0.45);}\n" +
                ".search-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".reset-btn{background:#f1f5f9;border:1px solid var(--border-color);padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;color:var(--text-secondary);display:inline-flex;align-items:center;gap:6px;}\n" +
                ".reset-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".reset-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".scan-btn{background:var(--success-color);color:white;border:none;padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;display:inline-flex;align-items:center;gap:6px;}\n" +
                ".scan-btn:hover{background:#389e0d;box-shadow:0 8px 20px -6px rgba(82,196,26,0.45);}\n" +
                ".scan-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".batch-fix-btn{background:var(--danger-color);color:white;border:none;padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;display:inline-flex;align-items:center;gap:6px;}\n" +
                ".batch-fix-btn:hover:not(:disabled){background:#ff7875;box-shadow:0 8px 20px -6px rgba(255,77,79,0.45);}\n" +
                ".batch-fix-btn:disabled{opacity:0.5;cursor:not-allowed;}\n" +
                ".batch-fix-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".log-card{background:var(--bg-card);border-radius:12px;overflow:hidden;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);}\n" +
                ".table-wrapper{overflow-x:auto;}\n" +
                ".log-table{width:100%;border-collapse:collapse;min-width:1400px;}\n" +
                ".log-table th{background:#f8fafc;padding:14px 16px;text-align:left;font-size:13px;font-weight:600;color:var(--text-secondary);border-bottom:1px solid var(--border-color);white-space:nowrap;}\n" +
                ".log-table td{padding:14px 16px;font-size:13px;color:var(--text-primary);border-bottom:1px solid var(--border-color);vertical-align:middle;}\n" +
                ".log-table tr:hover td{background:#f8fafc;}\n" +
                ".status-badge{display:inline-flex;align-items:center;gap:4px;padding:3px 10px;border-radius:20px;font-size:11px;font-weight:500;}\n" +
                ".status-dot{width:6px;height:6px;border-radius:50%;background:currentColor;}\n" +
                ".status-pending{background:rgba(250,173,20,0.15);color:var(--warn-color);}\n" +
                ".status-processed{background:rgba(82,196,26,0.15);color:var(--success-color);}\n" +
                ".status-ignored{background:rgba(148,163,184,0.15);color:#94a3b8;}\n" +
                ".alert-warning{background:rgba(250,173,20,0.15);color:var(--warn-color);}\n" +
                ".alert-critical{background:rgba(255,77,79,0.15);color:var(--danger-color);}\n" +
                ".log-table td.fee-cell{white-space:nowrap;line-height:1.5;background:inherit;}\n" +
                ".fee-exist-true{color:var(--success-color);font-weight:600;}\n" +
                ".fee-exist-true svg{width:14px;height:14px;stroke:currentColor;stroke-width:2;fill:none;vertical-align:middle;margin-right:4px;position:relative;top:-1px;}\n" +
                ".fee-exist-false{color:var(--text-muted);}\n" +
                ".fee-exist-false svg{width:14px;height:14px;stroke:currentColor;stroke-width:2;fill:none;vertical-align:middle;margin-right:4px;position:relative;top:-1px;}\n" +
                ".action-btn{padding:6px 14px;border-radius:40px;cursor:pointer;font-size:12px;transition:all 0.3s;border:none;font-weight:500;display:inline-flex;align-items:center;gap:4px;}\n" +
                ".action-btn svg{width:13px;height:13px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".action-btn.fix-btn{background:#e8f0fe;color:var(--primary-color);}\n" +
                ".action-btn.fix-btn:hover{background:var(--primary-color);color:white;transform:translateY(-1px);}\n" +
                ".action-btn.ignore-btn{background:#f1f5f9;color:var(--text-secondary);border:1px solid var(--border-color);}\n" +
                ".action-btn.ignore-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".action-btn:disabled{opacity:0.5;cursor:not-allowed;}\n" +
                ".pagination{display:flex;justify-content:center;align-items:center;gap:12px;padding:16px 20px;border-top:1px solid var(--border-color);background:#f8fafc;flex-wrap:wrap;}\n" +
                ".page-btn{padding:8px 16px;border:1px solid var(--border-color);background:var(--bg-card);color:var(--text-primary);border-radius:40px;cursor:pointer;font-size:13px;transition:all 0.3s;font-weight:500;}\n" +
                ".page-btn:hover:not(:disabled){border-color:var(--primary-color);color:var(--primary-color);transform:translateY(-1px);}\n" +
                ".page-btn:disabled{opacity:0.5;cursor:not-allowed;background:#f1f5f9;}\n" +
                ".page-info{font-size:13px;color:var(--text-muted);}\n" +
                ".empty{text-align:center;padding:48px;color:var(--text-muted);}\n" +
                ".footer{text-align:center;padding:32px 0;color:var(--text-muted);font-size:12px;border-top:1px solid var(--border-color);margin-top:32px;}\n" +
                ".modal{position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(15,32,64,0.45);z-index:10000;display:flex;align-items:center;justify-content:center;backdrop-filter:blur(4px);-webkit-backdrop-filter:blur(4px);}\n" +
                ".modal-content{background:var(--bg-card);border-radius:16px;width:550px;max-width:90%;padding:28px;box-shadow:0 24px 60px -12px rgba(15,45,105,0.25);border:1px solid var(--border-color);}\n" +
                ".modal-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:20px;padding-bottom:16px;border-bottom:1px solid var(--border-color);}\n" +
                ".modal-header h3{color:var(--text-primary);margin:0;font-size:17px;font-weight:600;display:flex;align-items:center;gap:8px;}\n" +
                ".modal-header h3 svg{width:18px;height:18px;stroke:var(--warn-color);stroke-width:2;fill:none;}\n" +
                ".modal-close{background:none;border:none;font-size:28px;cursor:pointer;color:var(--text-muted);line-height:1;transition:color 0.2s;}\n" +
                ".modal-close:hover{color:var(--text-primary);}\n" +
                ".modal-body{color:var(--text-secondary);font-size:14px;line-height:1.9;margin-bottom:20px;}\n" +
                ".modal-body .highlight{color:var(--danger-color);font-weight:600;}\n" +
                ".modal-body code{background:#f1f5f9;padding:2px 8px;border-radius:6px;font-size:13px;word-break:break-all;color:var(--text-primary);}\n" +
                ".modal-buttons{display:flex;gap:12px;justify-content:flex-end;margin-top:16px;padding-top:16px;border-top:1px solid var(--border-color);}\n" +
                ".modal-buttons .confirm-btn{background:var(--primary-color);color:white;border:none;padding:10px 26px;border-radius:10px;cursor:pointer;font-weight:500;font-size:14px;transition:all 0.3s;display:inline-flex;align-items:center;gap:6px;}\n" +
                ".modal-buttons .confirm-btn:hover{background:var(--primary-hover);box-shadow:0 8px 20px -6px rgba(22,119,255,0.45);}\n" +
                ".modal-buttons .confirm-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".modal-buttons .cancel-btn{background:#f1f5f9;border:1px solid var(--border-color);padding:10px 26px;border-radius:10px;cursor:pointer;color:var(--text-secondary);font-weight:500;font-size:14px;transition:all 0.3s;}\n" +
                ".modal-buttons .cancel-btn:hover{background:#e8ecf1;}\n" +
                ".fee-help-icon{display:inline-flex;align-items:center;justify-content:center;width:16px;height:16px;font-size:11px;font-weight:700;color:var(--primary-color);background:#e8f0fe;border-radius:50%;cursor:help;transition:all 0.3s;margin-left:2px;font-family:'Inter',sans-serif;line-height:1;user-select:none;}\n" +
                ".fee-help-icon:hover{background:var(--primary-color);color:#ffffff;transform:scale(1.1);}\n" +
                ".modal-content-wide{width:650px !important;max-width:95% !important;}\n" +
                ".numbers-info{display:flex;gap:24px;flex-wrap:wrap;font-size:13px;color:var(--text-secondary);margin-bottom:16px;}\n" +
                ".numbers-info code{background:#f1f5f9;padding:3px 10px;border-radius:6px;font-size:12px;word-break:break-all;color:var(--text-primary);}\n" +
                ".numbers-info .highlight{color:var(--danger-color);font-weight:600;}\n" +
                ".numbers-table-wrap{border:1px solid var(--border-color);border-radius:10px;max-height:400px;overflow:auto;}\n" +
                ".numbers-table-wrap table{width:100%;border-collapse:collapse;}\n" +
                ".numbers-table-wrap th{position:sticky;top:0;background:#f8fafc;z-index:1;padding:12px 16px;text-align:left;font-size:12px;font-weight:600;color:var(--text-secondary);border-bottom:1px solid var(--border-color);}\n" +
                ".numbers-table-wrap td{padding:10px 16px;font-size:13px;color:var(--text-primary);border-bottom:1px solid var(--border-color);}\n" +
                ".numbers-table-wrap tr:hover td{background:#f8fafc;}\n" +
                ".numbers-pagination{display:flex;justify-content:center;align-items:center;gap:12px;padding:16px 0 0 0;flex-wrap:wrap;}\n" +
                ".numbers-pagination .page-btn{padding:6px 14px;border:1px solid var(--border-color);background:var(--bg-card);color:var(--text-primary);border-radius:40px;cursor:pointer;font-size:12px;transition:all 0.3s;font-weight:500;}\n" +
                ".numbers-pagination .page-btn:hover:not(:disabled){border-color:var(--primary-color);color:var(--primary-color);transform:translateY(-1px);}\n" +
                ".numbers-pagination .page-btn:disabled{opacity:0.5;cursor:not-allowed;background:#f1f5f9;}\n" +
                ".numbers-pagination .page-info{font-size:12px;color:var(--text-muted);}\n" +
                ".numbers-pagination .total-info{font-size:12px;color:var(--text-muted);margin-right:8px;}\n" +
                "@media(max-width:1024px){.stats-grid{grid-template-columns:repeat(3,1fr);}}\n" +
                "@media(max-width:768px){.stats-grid{grid-template-columns:repeat(2,1fr);}.search-actions{margin-left:0;flex-wrap:wrap;}.nav-container{padding:0 20px;}.menu a{padding:6px 14px;font-size:12px;}.page-wrap{padding:20px;}.modal-content-wide{width:95% !important;}}\n" +
                "@media(max-width:480px){.stats-grid{grid-template-columns:1fr;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"navbar\">\n" +
                "<div class=\"nav-container\">\n" +
                "<div class=\"logo\">\n" +
                "<div class=\"logo-icon\">\n" +
                "<svg viewBox=\"0 0 24 24\"><path d=\"M3 3v18h18\"/><path d=\"M18.7 8l-5.1 5.2-2.8-2.7L7 14.3\"/></svg>\n" +
                "</div>\n" +
                "<div class=\"logo-text\">Texcell LTM 监控管理系统</div>\n" +
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
                "<a href=\"/monitor/dr-auto-fix\">定时任务配置</a>\n" +
                "<a href=\"/monitor/feature-management\">功能模块管理</a>\n" +
                "<a href=\"/monitor/subtask-management\">子任务队列</a>\n" +
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
                "<svg viewBox=\"0 0 24 24\"><path d=\"M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z\"/><polyline points=\"22,6 12,13 2,6\"/></svg>\n" +
                "队列任务DR监控\n" +
                "<span class=\"badge warning\" id=\"pendingBadge\">加载中...</span>\n" +
                "</div>\n" +
                "<div style=\"display:flex;gap:10px;flex-wrap:wrap;\">\n" +
                "<button class=\"batch-fix-btn\" id=\"batchFixBtn\" onclick=\"batchFix()\" disabled><svg viewBox=\"0 0 24 24\"><path d=\"M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z\"/></svg>批量修复</button>\n" +
                "<button class=\"scan-btn\" onclick=\"manualScan()\"><svg viewBox=\"0 0 24 24\"><polyline points=\"23 4 23 10 17 10\"/><polyline points=\"1 20 1 14 7 14\"/><path d=\"M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15\"/></svg>手动扫描</button>\n" +
                "</div>\n" +
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
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"3\" width=\"18\" height=\"18\" rx=\"2\"/><path d=\"M9 9h6v6H9z\"/></svg>任务ID</label><input type=\"text\" id=\"searchTaskId\" placeholder=\"模糊查询\"></div>\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><path d=\"M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2\"/><circle cx=\"12\" cy=\"7\" r=\"4\"/></svg>客户账号</label><input type=\"text\" id=\"searchSubAccount\" placeholder=\"模糊查询\"></div>\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><path d=\"M12 8v8M8 12h8\"/></svg>状态</label><select id=\"searchStatus\"><option value=\"\">全部</option><option value=\"PENDING\">待处理</option><option value=\"PROCESSED\">已处理</option><option value=\"IGNORED\">已忽略</option></select></div>\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><path d=\"M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9\"/><path d=\"M13.73 21a2 2 0 0 1-3.46 0\"/></svg>告警级别</label><select id=\"searchAlertLevel\"><option value=\"\">全部</option><option value=\"WARNING\">警告(≥30分钟)</option><option value=\"CRITICAL\">严重(≥60分钟)</option></select></div>\n" +
                "<div class=\"search-actions\">\n" +
                "<button class=\"search-btn\" onclick=\"searchLogs()\"><svg viewBox=\"0 0 24 24\"><circle cx=\"11\" cy=\"11\" r=\"8\"/><line x1=\"21\" y1=\"21\" x2=\"16.65\" y2=\"16.65\"/></svg>查询</button>\n" +
                "<button class=\"reset-btn\" onclick=\"resetSearch()\"><svg viewBox=\"0 0 24 24\"><polyline points=\"23 4 23 10 17 10\"/><polyline points=\"1 20 1 14 7 14\"/><path d=\"M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15\"/></svg>重置</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"log-card\">\n" +
                "<div class=\"table-wrapper\">\n" +
                "<table class=\"log-table\">\n" +
                "<thead>\n" +
                "<tr>\n" +
                "<th style=\"width:40px;text-align:center;\"><input type=\"checkbox\" onclick=\"toggleAllTasks()\"></th>\n" +
                "<th>任务ID</th>\n" +
                "<th>客户账号</th>\n" +
                "<th>任务名称</th>\n" +
                "<th>总发送数</th>\n" +
                "<th>已提交</th>\n" +
                "<th>任务时间</th>\n" +
                "<th>DR未更新数</th>\n" +
                "<th>已过时间</th>\n" +
                "<th>告警级别</th>\n" +
                "<th>FEE是否存在记录</th>\n" +
                "<th>状态</th>\n" +
                "<th>操作人</th>\n" +
                "<th>操作</th>\n" +
                "</tr>\n" +
                "</thead>\n" +
                "<tbody id=\"logBody\"><tr><td colspan=\"15\" class=\"empty\">加载中...</td></tr></tbody>\n" +
                "</table>\n" +
                "</div>\n" +
                "<div class=\"pagination\" id=\"pagination\"></div>\n" +
                "</div>\n" +
                "<div class=\"footer\">队列任务DR监控 | 任务已完成但DR未更新 | 每10分钟自动扫描</div>\n" +
                "</div>\n" +
                "<!-- 确认弹窗 -->\n" +
                "<div id=\"confirmModal\" class=\"modal\" style=\"display:none;\">\n" +
                "<div class=\"modal-content\">\n" +
                "<div class=\"modal-header\">\n" +
                "<h3><svg viewBox=\"0 0 24 24\"><path d=\"M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z\"/><line x1=\"12\" y1=\"9\" x2=\"12\" y2=\"13\"/><line x1=\"12\" y1=\"17\" x2=\"12.01\" y2=\"17\"/></svg>确认执行修复</h3>\n" +
                "<button class=\"modal-close\" onclick=\"closeModal()\">&times;</button>\n" +
                "</div>\n" +
                "<div class=\"modal-body\" id=\"modalBody\">\n" +
                "<p>确认对以下任务执行修复？</p>\n" +
                "<p><strong>任务ID：</strong><code id=\"modalTaskId\">-</code></p>\n" +
                "<p><strong>客户账号：</strong><span id=\"modalSubAccount\">-</span></p>\n" +
                "<p><strong>总发送数：</strong><span id=\"modalTotalCount\">-</span></p>\n" +
                "<p><strong>DR未更新数：</strong><span class=\"highlight\" id=\"modalPendingCount\">-</span></p>\n" +
                "<p style=\"color:#faad14;margin-top:12px;\">请确认此任务的短信是否已经全部发送出去！</p>\n" +
                "<p style=\"color:#ff4d4f;\">执行后会将 <code>RecvDRStatus</code> 更新为 <code>DELIVRD</code>，<code>RecvDRError</code> 更新为 <code>000</code></p>\n" +
                "</div>\n" +
                "<div class=\"modal-buttons\">\n" +
                "<button class=\"confirm-btn\" onclick=\"confirmFix()\"><svg viewBox=\"0 0 24 24\"><path d=\"M22 11.08V12a10 10 0 1 1-5.93-9.14\"/><polyline points=\"22 4 12 14.01 9 11.01\"/></svg>确认执行</button>\n" +
                "<button class=\"cancel-btn\" onclick=\"closeModal()\">取消</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<!-- 查看未更新号码弹窗 -->\n" +
                "<div id=\"numbersModal\" class=\"modal\" style=\"display:none;\">\n" +
                "<div class=\"modal-content modal-content-wide\">\n" +
                "<div class=\"modal-header\">\n" +
                "<h3><svg viewBox=\"0 0 24 24\"><rect x=\"5\" y=\"2\" width=\"14\" height=\"20\" rx=\"2\" ry=\"2\"/><line x1=\"12\" y1=\"18\" x2=\"12.01\" y2=\"18\"/></svg>未更新DR号码列表</h3>\n" +
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
                "<tr><td colspan=\"2\" style=\"padding:30px;text-align:center;color:#8a97a8;\">加载中...</td></tr>\n" +
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
                "var selectedTaskIds = [];\n" +
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
                "                var roleText = data.data.role === 'ADMIN' ? '管理员' : 'NOC';\n" +
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
                "                var list = data.data || [];\n" +
                "                renderTable(list);\n" +
                "                totalPages = data.totalPages || 1;\n" +
                "                renderPagination();\n" +
                "            } else {\n" +
                "                document.getElementById('logBody').innerHTML = '<tr><td colspan=\"14\" class=\"empty\">加载失败: ' + (data.msg || '错误') + '</td></tr>';\n" +
                "            }\n" +
                "        }).catch(function() {\n" +
                "            document.getElementById('logBody').innerHTML = '<tr><td colspan=\"14\" class=\"empty\">加载失败: 网络错误</td></tr>';\n" +
                "        });\n" +
                "}\n" +
                "\n" +
                "function toggleAllTasks() {\n" +
                "    var checkboxes = document.querySelectorAll('.task-checkbox');\n" +
                "    var allChecked = true;\n" +
                "\n" +
                "    checkboxes.forEach(function(cb) {\n" +
                "        if (!cb.disabled && !cb.checked) {\n" +
                "            allChecked = false;\n" +
                "        }\n" +
                "    });\n" +
                "\n" +
                "    checkboxes.forEach(function(cb) {\n" +
                "        if (!cb.disabled) {\n" +
                "            cb.checked = !allChecked;\n" +
                "            var taskId = cb.value;\n" +
                "            if (cb.checked) {\n" +
                "                if (!selectedTaskIds.includes(taskId)) {\n" +
                "                    selectedTaskIds.push(taskId);\n" +
                "                }\n" +
                "            } else {\n" +
                "                var index = selectedTaskIds.indexOf(taskId);\n" +
                "                if (index > -1) {\n" +
                "                    selectedTaskIds.splice(index, 1);\n" +
                "                }\n" +
                "            }\n" +
                "        }\n" +
                "    });\n" +
                "    updateBatchFixBtn();\n" +
                "}\n" +
                "\n" +
                "function toggleTask(taskId) {\n" +
                "    var checkbox = document.getElementById('check_' + taskId);\n" +
                "    if (!checkbox) return;\n" +
                "    if (checkbox.checked) {\n" +
                "        if (!selectedTaskIds.includes(taskId)) {\n" +
                "            selectedTaskIds.push(taskId);\n" +
                "        }\n" +
                "    } else {\n" +
                "        var index = selectedTaskIds.indexOf(taskId);\n" +
                "        if (index > -1) {\n" +
                "            selectedTaskIds.splice(index, 1);\n" +
                "        }\n" +
                "    }\n" +
                "    updateBatchFixBtn();\n" +
                "}\n" +
                "\n" +
                "function updateBatchFixBtn() {\n" +
                "    var btn = document.getElementById('batchFixBtn');\n" +
                "    var iconSvg = '<svg viewBox=\"0 0 24 24\"><path d=\"M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z\"/></svg>';\n" +
                "    if (selectedTaskIds.length > 0) {\n" +
                "        btn.disabled = false;\n" +
                "        btn.innerHTML = iconSvg + '批量修复 (' + selectedTaskIds.length + '个)';\n" +
                "    } else {\n" +
                "        btn.disabled = true;\n" +
                "        btn.innerHTML = iconSvg + '批量修复';\n" +
                "    }\n" +
                "}\n" +
                "\n" +
                "function batchFix() {\n" +
                "    if (selectedTaskIds.length === 0) {\n" +
                "        alert('请先选择要修复的任务');\n" +
                "        return;\n" +
                "    }\n" +
                "    if (!confirm('确认批量修复 ' + selectedTaskIds.length + ' 个任务？\\n\\n会将 RecvDRStatus 更新为 DELIVRD，RecvDRError 更新为 000')) {\n" +
                "        return;\n" +
                "    }\n" +
                "    var btn = document.getElementById('batchFixBtn');\n" +
                "    var iconSvg = '<svg viewBox=\"0 0 24 24\"><path d=\"M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z\"/></svg>';\n" +
                "    btn.disabled = true;\n" +
                "    btn.innerHTML = '修复中...';\n" +
                "    fetch('/api/monitor/queue-monitor/batch-fix?secret=dn17ORUwf36Znl', {\n" +
                "        method: 'POST',\n" +
                "        headers: { 'Content-Type': 'application/json' },\n" +
                "        body: JSON.stringify({ taskIds: selectedTaskIds })\n" +
                "    }).then(function(res) { return res.json(); })\n" +
                "      .then(function(data) {\n" +
                "          btn.disabled = false;\n" +
                "          btn.innerHTML = iconSvg + '批量修复';\n" +
                "          if (data.code === 200) {\n" +
                "              alert(data.msg);\n" +
                "              selectedTaskIds = [];\n" +
                "              loadLogs();\n" +
                "              loadStats();\n" +
                "          } else {\n" +
                "              alert('修复失败: ' + (data.msg || '未知错误'));\n" +
                "          }\n" +
                "      }).catch(function(err) {\n" +
                "          btn.disabled = false;\n" +
                "          btn.innerHTML = iconSvg + '批量修复';\n" +
                "          alert('网络错误: ' + err.message);\n" +
                "      });\n" +
                "}\n" +
                "\n" +
                "function renderTable(list) {\n" +
                "    var tbody = document.getElementById('logBody');\n" +
                "    if (!tbody) return;\n" +
                "    if (!list || list.length === 0) {\n" +
                "        tbody.innerHTML = '<tr><td colspan=\"14\" class=\"empty\">暂无数据</td></tr>';\n" +
                "        return;\n" +
                "    }\n" +
                "    var html = '';\n" +
                "    var statusMap = { 'PENDING': '待处理', 'PROCESSED': '已处理', 'IGNORED': '已忽略' };\n" +
                "    var statusClassMap = { 'PENDING': 'status-pending', 'PROCESSED': 'status-processed', 'IGNORED': 'status-ignored' };\n" +
                "    var alertMap = { 'WARNING': '警告', 'CRITICAL': '严重' };\n" +
                "    var alertClassMap = { 'WARNING': 'alert-warning', 'CRITICAL': 'alert-critical' };\n" +
                "    var checkSvg = '<svg viewBox=\"0 0 24 24\"><path d=\"M22 11.08V12a10 10 0 1 1-5.93-9.14\"/><polyline points=\"22 4 12 14.01 9 11.01\"/></svg>';\n" +
                "    var clockSvg = '<svg viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><polyline points=\"12 6 12 12 16 14\"/></svg>';\n" +
                "    for (var i = 0; i < list.length; i++) {\n" +
                "        var log = list[i];\n" +
                "        var canFix = log.status === 'PENDING';\n" +
                "        var canIgnore = log.status === 'PENDING';\n" +
                "        var pendingCount = log.detailPendingCount || 0;\n" +
                "        var taskId = log.taskId || '';\n" +
                "        var feeExist = log.feeExist === true;\n" +
                "        var feeExistClass = feeExist ? 'fee-exist-true' : 'fee-exist-false';\n" +
                "        var feeDisplay = feeExist ? (checkSvg + '已存在') : (log.status === 'PENDING' ? '<span style=\"display:inline-flex;align-items:center;gap:2px;\">' + clockSvg + '待扫描<span class=\"fee-help-icon\" title=\"如果一直处于待扫描状态，请挑选样本号码手动去FEE表验证是否存在回执记录\">?</span></span>' : '-');\n" +
                "        html += '<tr>' +\n" +
                "            '<td style=\"text-align:center;\"><input type=\"checkbox\" class=\"task-checkbox\" id=\"check_' + escapeHtml(taskId) + '\" value=\"' + escapeHtml(taskId) + '\" onchange=\"toggleTask(\\'' + escapeHtml(taskId) + '\\')\" ' + (log.status !== 'PENDING' ? 'disabled' : '') + '></td>' +\n" +
                "            '<td style=\"font-family:monospace;font-size:12px;color:#475569;\" title=\"' + escapeHtml(taskId) + '\">' + escapeHtml((taskId || '').substring(0, 25)) + '...</td>' +\n" +
                "            '<td>' + escapeHtml(log.subAccount || '-') + '</td>' +\n" +
                "            '<td>' + escapeHtml((log.taskName || '-').substring(0, 30)) + '</td>' +\n" +
                "            '<td>' + (log.totalCount || 0) + '</td>' +\n" +
                "            '<td>' + (log.submitSuccCount || 0) + '</td>' +\n" +
                "            '<td style=\"white-space:nowrap;\">' + (log.taskTime ? formatDateTime(log.taskTime) : '-') + '</td>' +\n" +
                "            '<td>' +\n" +
                "                (pendingCount > 0 \n" +
                "                    ? '<a href=\"javascript:void(0)\" style=\"color:#ff4d4f;font-weight:600;text-decoration:underline;cursor:pointer;\" onclick=\"showPendingNumbers(\\'' + escapeHtml(taskId) + '\\', ' + pendingCount + ')\">' + pendingCount + '</a>' \n" +
                "                    : '<span style=\"color:#8a97a8;\">0</span>'\n" +
                "                ) +\n" +
                "            '</td>' +\n" +
                "            '<td>' + (log.elapsedMinutes || 0) + '分钟</td>' +\n" +
                "            '<td><span class=\"status-badge ' + alertClassMap[log.alertLevel] + '\"><span class=\"status-dot\"></span>' + alertMap[log.alertLevel] + '</span></td>' +\n" +
                "            '<td class=\"fee-cell ' + feeExistClass + '\">' + feeDisplay + '</td>' +\n" +
                "            '<td><span class=\"status-badge ' + statusClassMap[log.status] + '\"><span class=\"status-dot\"></span>' + statusMap[log.status] + '</span></td>' +\n" +
                "            '<td>' + (log.processedBy || '-') + '</td>' +\n" +
                "            '<td>';\n" +
                "        if (canFix) {\n" +
                "            html += '<button class=\"action-btn fix-btn\" onclick=\"showConfirmModal(\\'' + escapeHtml(taskId) + '\\', \\'' + escapeHtml(log.subAccount || '-') + '\\', ' + (log.totalCount || 0) + ', ' + pendingCount + ')\"><svg viewBox=\"0 0 24 24\"><path d=\"M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z\"/></svg>修复</button> ';\n" +
                "        }\n" +
                "        if (canIgnore) {\n" +
                "            html += '<button class=\"action-btn ignore-btn\" onclick=\"ignoreTask(\\'' + escapeHtml(taskId) + '\\')\"><svg viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><line x1=\"4.93\" y1=\"4.93\" x2=\"19.07\" y2=\"19.07\"/></svg>忽略</button>';\n" +
                "        }\n" +
                "        if (!canFix && !canIgnore) {\n" +
                "            html += '-';\n" +
                "        }\n" +
                "        html += '</td></tr>';\n" +
                "    }\n" +
                "    tbody.innerHTML = html;\n" +
                "    updateBatchFixBtn();\n" +
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
                "          btn.innerHTML = '<svg viewBox=\"0 0 24 24\"><path d=\"M22 11.08V12a10 10 0 1 1-5.93-9.14\"/><polyline points=\"22 4 12 14.01 9 11.01\"/></svg>确认执行';\n" +
                "          if (data.code === 200) {\n" +
                "              alert('修复成功！更新了 ' + data.updated + ' 条记录');\n" +
                "              closeModal();\n" +
                "              loadLogs();\n" +
                "              loadStats();\n" +
                "          } else {\n" +
                "              alert('修复失败: ' + (data.msg || '未知错误'));\n" +
                "          }\n" +
                "      }).catch(function(err) {\n" +
                "          btn.disabled = false;\n" +
                "          btn.innerHTML = '<svg viewBox=\"0 0 24 24\"><path d=\"M22 11.08V12a10 10 0 1 1-5.93-9.14\"/><polyline points=\"22 4 12 14.01 9 11.01\"/></svg>确认执行';\n" +
                "          alert('网络错误: ' + err.message);\n" +
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
                "    var originalHtml = btn.innerHTML;\n" +
                "    btn.disabled = true;\n" +
                "    btn.innerHTML = '扫描中...';\n" +
                "    var overlay = document.createElement('div');\n" +
                "    overlay.id = 'scanOverlay';\n" +
                "    overlay.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(15,32,64,0.45);z-index:9998;backdrop-filter:blur(4px);-webkit-backdrop-filter:blur(4px);';\n" +
                "    document.body.appendChild(overlay);\n" +
                "    var modal = document.createElement('div');\n" +
                "    modal.id = 'scanModal';\n" +
                "    modal.style.cssText = 'position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);z-index:9999;background:#ffffff;border-radius:20px;padding:40px 50px;min-width:420px;box-shadow:0 24px 60px -12px rgba(15,45,105,0.25);text-align:center;animation:slideUp 0.4s ease;border:1px solid #e8ecf1;';\n" +
                "    modal.innerHTML = '<div style=\"margin-bottom:20px;\"><span style=\"display:inline-block;animation:spin 1.2s linear infinite;\"><svg width=\"40\" height=\"40\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#1677ff\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><polyline points=\"23 4 23 10 17 10\"/><polyline points=\"1 20 1 14 7 14\"/><path d=\"M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15\"/></svg></span></div><h3 style=\"color:#1e293b;font-size:18px;margin-bottom:8px;font-weight:600;\">正在扫描超时任务...</h3><p style=\"color:#8a97a8;font-size:14px;margin-bottom:20px;\">请稍候，正在检查未更新DR的队列任务</p><div style=\"background:#f1f5f9;border-radius:12px;height:10px;overflow:hidden;margin-bottom:16px;position:relative;\"><div id=\"scanBar\" style=\"width:0%;height:100%;background:linear-gradient(135deg,#1677ff 0%,#3c8dff 50%,#52c41a 100%);background-size:300% 100%;border-radius:12px;transition:width 0.6s cubic-bezier(0.22,1,0.36,1);animation:shimmer 1.5s ease-in-out infinite;\"></div></div><div style=\"display:flex;justify-content:space-between;font-size:13px;color:#8a97a8;\"><span id=\"scanStatus\">正在连接服务器...</span><span id=\"scanPercent\" style=\"font-weight:600;color:#1677ff;\">0%</span></div><div style=\"margin-top:16px;font-size:12px;color:#cbd5e1;\">预计耗时 5-30 秒</div>';\n" +
                "    document.body.appendChild(modal);\n" +
                "    var style = document.createElement('style');\n" +
                "    style.textContent = '@keyframes slideUp{from{opacity:0;transform:translate(-50%,-40%);}to{opacity:1;transform:translate(-50%,-50%);}}@keyframes spin{0%{transform:rotate(0deg);}100%{transform:rotate(360deg);}}@keyframes shimmer{0%{background-position:0% 50%;}50%{background-position:100% 50%;}100%{background-position:0% 50%;}}';\n" +
                "    document.head.appendChild(style);\n" +
                "    var bar = document.getElementById('scanBar');\n" +
                "    var status = document.getElementById('scanStatus');\n" +
                "    var percent = document.getElementById('scanPercent');\n" +
                "    var progress = 0;\n" +
                "    var statuses = ['正在连接服务器...', '查询超时任务...', '分析任务状态...', '更新告警记录...', '扫描完成！'];\n" +
                "    var statusIndex = 0;\n" +
                "    var interval = setInterval(function() {\n" +
                "        if (progress < 90) {\n" +
                "            progress += Math.random() * 4 + 1;\n" +
                "            if (progress > 90) progress = 90;\n" +
                "            bar.style.width = progress + '%';\n" +
                "            percent.innerText = Math.round(progress) + '%';\n" +
                "        }\n" +
                "        if (progress > 20 && statusIndex < 1) { statusIndex = 1; status.innerText = statuses[1]; }\n" +
                "        if (progress > 50 && statusIndex < 2) { statusIndex = 2; status.innerText = statuses[2]; }\n" +
                "        if (progress > 70 && statusIndex < 3) { statusIndex = 3; status.innerText = statuses[3]; }\n" +
                "    }, 400);\n" +
                "    var startTime = Date.now();\n" +
                "    fetch('/api/monitor/queue-monitor/scan?secret=dn17ORUwf36Znl', { method: 'POST' })\n" +
                "        .then(function(res) { return res.json(); })\n" +
                "        .then(function(data) {\n" +
                "            clearInterval(interval);\n" +
                "            bar.style.width = '100%';\n" +
                "            percent.innerText = '100%';\n" +
                "            status.innerText = '扫描完成！';\n" +
                "            status.style.color = '#52c41a';\n" +
                "            var elapsed = Math.round((Date.now() - startTime) / 1000);\n" +
                "            btn.disabled = false;\n" +
                "            btn.innerHTML = originalHtml;\n" +
                "            if (data.code === 200) {\n" +
                "                var detailMsg = '扫描结果（耗时 ' + elapsed + ' 秒）：\\n\\n' +\n" +
                "                    '   新增告警: ' + (data.newAlerts || 0) + ' 条\\n' +\n" +
                "                    '   更新告警: ' + (data.updatedAlerts || 0) + ' 条\\n' +\n" +
                "                    '   自动关闭: ' + (data.autoClosed || 0) + ' 条\\n' +\n" +
                "                    '   企微发送: ' + (data.weChatAlerts || 0) + ' 条\\n' +\n" +
                "                    '   总任务数: ' + (data.total || 0) + ' 个';\n" +
                "                setTimeout(function() {\n" +
                "                    closeScanModal();\n" +
                "                    alert(detailMsg);\n" +
                "                    loadLogs();\n" +
                "                    loadStats();\n" +
                "                }, 800);\n" +
                "            } else {\n" +
                "                status.innerText = '扫描失败: ' + (data.msg || '');\n" +
                "                status.style.color = '#ff4d4f';\n" +
                "                setTimeout(function() { closeScanModal(); alert('扫描失败: ' + (data.msg || '未知错误')); }, 1200);\n" +
                "            }\n" +
                "        }).catch(function(err) {\n" +
                "            clearInterval(interval);\n" +
                "            btn.disabled = false;\n" +
                "            btn.innerHTML = originalHtml;\n" +
                "            bar.style.width = '100%';\n" +
                "            status.innerText = '网络错误';\n" +
                "            status.style.color = '#ff4d4f';\n" +
                "            setTimeout(function() { closeScanModal(); alert('网络错误: ' + err.message); }, 1200);\n" +
                "        });\n" +
                "}\n" +
                "\n" +
                "function closeScanModal() {\n" +
                "    var overlay = document.getElementById('scanOverlay');\n" +
                "    var modal = document.getElementById('scanModal');\n" +
                "    if (overlay) overlay.remove();\n" +
                "    if (modal) modal.remove();\n" +
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
                "        tbody.innerHTML = '<tr><td colspan=\"2\" style=\"padding:30px;text-align:center;color:#8a97a8;\">暂无数据</td></tr>';\n" +
                "        renderNumbersPagination(0, totalPages);\n" +
                "        return;\n" +
                "    }\n" +
                "    var html = '';\n" +
                "    var startIndex = (numbersPage - 1) * numbersSize + 1;\n" +
                "    for (var i = 0; i < list.length; i++) {\n" +
                "        var item = list[i];\n" +
                "        var mobileNumber = item.mobileNumber || '-';\n" +
                "        html += '<tr>' +\n" +
                "            '<td style=\"text-align:center;color:#8a97a8;font-size:12px;\">' + (startIndex + i) + '</td>' +\n" +
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

    private String generateDrAutoFixHtml() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=no\">\n" +
                "<title>Texcell LTM WEB 定时任务配置</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='22' fill='%231677ff'/%3E%3Ctext x='50' y='72' font-size='60' font-weight='bold' text-anchor='middle' fill='white' font-family='Arial'%3ET%3C/text%3E%3C/svg%3E\">\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:ital,wght@0,300;0,400;0,500;0,600;0,700;1,400&display=swap\" rel=\"stylesheet\">\n" +
                "<style>\n" +
                ":root {\n" +
                "  --primary-color:#1677ff;\n" +
                "  --primary-hover:#3c8dff;\n" +
                "  --text-primary:#1e293b;\n" +
                "  --text-secondary:#475569;\n" +
                "  --text-muted:#8a97a8;\n" +
                "  --input-border:#d9e2ec;\n" +
                "  --bg-page:#f5f7fa;\n" +
                "  --bg-card:#ffffff;\n" +
                "  --border-color:#e8ecf1;\n" +
                "  --shadow-sm:0 1px 3px rgba(0,0,0,0.06);\n" +
                "  --shadow-hover:0 4px 12px rgba(22,119,255,0.1);\n" +
                "  --success-color:#52c41a;\n" +
                "  --danger-color:#ff4d4f;\n" +
                "  --warn-color:#faad14;\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:var(--bg-page);font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;}\n" +
                ".navbar{background:var(--bg-card);box-shadow:var(--shadow-sm);border-bottom:1px solid var(--border-color);position:sticky;top:0;z-index:100;}\n" +
                ".nav-container{max-width:2100px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;padding:0 32px;height:70px;}\n" +
                ".logo{display:flex;align-items:center;gap:12px;}\n" +
                ".logo-icon{width:40px;height:40px;background:linear-gradient(135deg,var(--primary-color) 0%,var(--primary-hover) 100%);border-radius:12px;display:flex;align-items:center;justify-content:center;}\n" +
                ".logo-icon svg{width:22px;height:22px;stroke:#fff;stroke-width:2;fill:none;}\n" +
                ".logo-text{font-size:18px;font-weight:700;color:var(--text-primary);letter-spacing:1px;}\n" +
                ".logo-sub{font-size:11px;color:var(--text-muted);margin-left:8px;}\n" +
                ".menu{display:flex;gap:12px;}\n" +
                ".menu a{color:var(--text-secondary);text-decoration:none;padding:8px 20px;border-radius:40px;transition:all 0.3s;font-weight:500;font-size:14px;}\n" +
                ".menu a:hover{background:#f1f5f9;color:var(--text-primary);}\n" +
                ".menu a.active{background:#e8f0fe;color:var(--primary-color);box-shadow:none;}\n" +
                ".user-info{display:flex;align-items:center;gap:20px;}\n" +
                ".user-name{color:var(--text-secondary);font-size:14px;}\n" +
                ".user-role{background:rgba(22,119,255,0.1);padding:4px 12px;border-radius:20px;font-size:12px;color:var(--primary-color);}\n" +
                ".logout-btn{background:#f1f5f9;border:1px solid var(--border-color);color:var(--text-secondary);padding:8px 20px;border-radius:40px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".page-wrap{max-width:2100px;margin:0 auto;padding:32px;}\n" +
                ".page-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:28px;flex-wrap:wrap;gap:16px;}\n" +
                ".page-title{font-size:22px;font-weight:700;color:var(--text-primary);display:flex;align-items:center;gap:12px;}\n" +
                ".page-title svg{width:24px;height:24px;stroke:var(--primary-color);stroke-width:2;fill:none;}\n" +
                ".search-card{background:var(--bg-card);border-radius:12px;padding:24px;margin-bottom:32px;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);}\n" +
                ".search-row{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-end;}\n" +
                ".search-group{display:flex;flex-direction:column;gap:6px;}\n" +
                ".search-group label{font-size:12px;color:var(--text-secondary);font-weight:500;display:flex;align-items:center;gap:6px;}\n" +
                ".search-group label svg{width:14px;height:14px;stroke:var(--text-muted);stroke-width:2;fill:none;}\n" +
                ".search-group input,.search-group select{padding:10px 12px;border:1px solid var(--input-border);border-radius:10px;font-size:13px;min-width:170px;background:var(--bg-card);color:var(--text-primary);font-family:inherit;transition:all 0.3s;}\n" +
                ".search-group input:focus,.search-group select:focus{outline:none;border-color:var(--primary-color);box-shadow:0 0 0 3px rgba(22,119,255,0.12);}\n" +
                ".search-actions{display:flex;gap:10px;margin-left:auto;align-items:center;flex-wrap:wrap;}\n" +
                ".search-btn{background:var(--primary-color);color:white;border:none;padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;display:inline-flex;align-items:center;gap:6px;}\n" +
                ".search-btn:hover{background:var(--primary-hover);box-shadow:0 8px 20px -6px rgba(22,119,255,0.45);}\n" +
                ".search-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".reset-btn{background:#f1f5f9;border:1px solid var(--border-color);padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;color:var(--text-secondary);display:inline-flex;align-items:center;gap:6px;}\n" +
                ".reset-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".reset-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".add-btn{background:var(--success-color);color:white;border:none;padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;display:inline-flex;align-items:center;gap:6px;}\n" +
                ".add-btn:hover{background:#389e0d;box-shadow:0 8px 20px -6px rgba(82,196,26,0.45);}\n" +
                ".add-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".log-card{background:var(--bg-card);border-radius:12px;overflow:hidden;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);}\n" +
                ".table-wrapper{overflow-x:auto;}\n" +
                ".log-table{width:100%;border-collapse:collapse;min-width:900px;}\n" +
                ".log-table th{background:#f8fafc;padding:14px 16px;text-align:left;font-size:13px;font-weight:600;color:var(--text-secondary);border-bottom:1px solid var(--border-color);white-space:nowrap;}\n" +
                ".log-table td{padding:14px 16px;font-size:13px;color:var(--text-primary);border-bottom:1px solid var(--border-color);vertical-align:middle;}\n" +
                ".log-table tr:hover td{background:#f8fafc;}\n" +
                ".status-badge{display:inline-flex;align-items:center;gap:4px;padding:3px 10px;border-radius:20px;font-size:11px;font-weight:500;}\n" +
                ".status-dot{width:6px;height:6px;border-radius:50%;background:currentColor;}\n" +
                ".status-enabled{background:rgba(82,196,26,0.1);color:var(--success-color);}\n" +
                ".status-disabled{background:rgba(255,77,79,0.1);color:var(--danger-color);}\n" +
                ".action-btn{padding:6px 14px;border-radius:40px;cursor:pointer;font-size:12px;transition:all 0.3s;border:none;font-weight:500;display:inline-flex;align-items:center;gap:4px;}\n" +
                ".action-btn svg{width:13px;height:13px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".action-btn.edit-btn{background:#e8f0fe;color:var(--primary-color);}\n" +
                ".action-btn.edit-btn:hover{background:var(--primary-color);color:white;transform:translateY(-1px);}\n" +
                ".action-btn.toggle-btn{background:rgba(250,173,20,0.15);color:var(--warn-color);margin-left:6px;}\n" +
                ".action-btn.toggle-btn:hover{background:var(--warn-color);color:white;transform:translateY(-1px);}\n" +
                ".action-btn.delete-btn{background:rgba(255,77,79,0.1);color:var(--danger-color);margin-left:6px;}\n" +
                ".action-btn.delete-btn:hover{background:var(--danger-color);color:white;transform:translateY(-1px);}\n" +
                ".pagination{display:flex;justify-content:center;align-items:center;gap:12px;padding:16px 20px;border-top:1px solid var(--border-color);background:#f8fafc;flex-wrap:wrap;}\n" +
                ".page-btn{padding:8px 16px;border:1px solid var(--border-color);background:var(--bg-card);color:var(--text-primary);border-radius:40px;cursor:pointer;font-size:13px;transition:all 0.3s;font-weight:500;}\n" +
                ".page-btn:hover:not(:disabled){border-color:var(--primary-color);color:var(--primary-color);transform:translateY(-1px);}\n" +
                ".page-btn:disabled{opacity:0.5;cursor:not-allowed;background:#f1f5f9;}\n" +
                ".page-info{font-size:13px;color:var(--text-muted);}\n" +
                ".empty{text-align:center;padding:48px;color:var(--text-muted);}\n" +
                ".footer{text-align:center;padding:32px 0;color:var(--text-muted);font-size:12px;border-top:1px solid var(--border-color);margin-top:32px;}\n" +
                ".modal{position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(15,32,64,0.45);z-index:10000;display:flex;align-items:center;justify-content:center;backdrop-filter:blur(4px);-webkit-backdrop-filter:blur(4px);}\n" +
                ".modal-content{background:var(--bg-card);border-radius:16px;width:600px;max-width:90%;max-height:90vh;overflow-y:auto;padding:28px;box-shadow:0 24px 60px -12px rgba(15,45,105,0.25);border:1px solid var(--border-color);}\n" +
                ".modal-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:24px;padding-bottom:16px;border-bottom:1px solid var(--border-color);}\n" +
                ".modal-header h3{color:var(--text-primary);margin:0;font-size:17px;font-weight:600;display:flex;align-items:center;gap:8px;}\n" +
                ".modal-header h3 svg{width:18px;height:18px;stroke:var(--primary-color);stroke-width:2;fill:none;}\n" +
                ".modal-close{background:none;border:none;font-size:28px;cursor:pointer;color:var(--text-muted);line-height:1;transition:color 0.2s;}\n" +
                ".modal-close:hover{color:var(--text-primary);}\n" +
                ".modal-body{color:var(--text-secondary);font-size:14px;}\n" +
                ".modal-buttons{display:flex;gap:12px;justify-content:flex-end;margin-top:24px;padding-top:16px;border-top:1px solid var(--border-color);}\n" +
                ".modal-buttons .confirm-btn{background:var(--primary-color);color:white;border:none;padding:10px 26px;border-radius:10px;cursor:pointer;font-weight:500;font-size:14px;transition:all 0.3s;display:inline-flex;align-items:center;gap:6px;}\n" +
                ".modal-buttons .confirm-btn:hover{background:var(--primary-hover);box-shadow:0 8px 20px -6px rgba(22,119,255,0.45);}\n" +
                ".modal-buttons .confirm-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".modal-buttons .cancel-btn{background:#f1f5f9;border:1px solid var(--border-color);padding:10px 26px;border-radius:10px;cursor:pointer;color:var(--text-secondary);font-weight:500;font-size:14px;transition:all 0.3s;}\n" +
                ".modal-buttons .cancel-btn:hover{background:#e8ecf1;}\n" +
                ".form-group{margin-bottom:18px;}\n" +
                ".form-group label{display:block;font-size:13px;font-weight:500;color:var(--text-secondary);margin-bottom:7px;}\n" +
                ".form-group label .required{color:var(--danger-color);}\n" +
                ".form-group input,.form-group select,.form-group textarea{width:100%;padding:11px 14px;border:1px solid var(--input-border);border-radius:10px;font-size:14px;background:var(--bg-card);color:var(--text-primary);font-family:inherit;transition:border-color .2s,box-shadow .2s;}\n" +
                ".form-group input:focus,.form-group select:focus,.form-group textarea:focus{outline:none;border-color:var(--primary-color);box-shadow:0 0 0 3px rgba(22,119,255,0.12);}\n" +
                ".form-group input[readonly]{background:#f1f5f9;color:var(--text-muted);cursor:not-allowed;}\n" +
                ".form-group textarea{resize:vertical;min-height:60px;}\n" +
                ".form-group .help-text{font-size:11px;color:var(--text-muted);margin-top:6px;}\n" +
                ".form-row{display:grid;grid-template-columns:1fr 1fr;gap:16px;}\n" +
                ".form-row .form-group{margin-bottom:0;}\n" +
                "@media(max-width:768px){.form-row{grid-template-columns:1fr;}.search-actions{margin-left:0;flex-wrap:wrap;}.nav-container{padding:0 20px;}.menu a{padding:6px 14px;font-size:12px;}.page-wrap{padding:20px;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"navbar\">\n" +
                "<div class=\"nav-container\">\n" +
                "<div class=\"logo\">\n" +
                "<div class=\"logo-icon\">\n" +
                "<svg viewBox=\"0 0 24 24\"><path d=\"M3 3v18h18\"/><path d=\"M18.7 8l-5.1 5.2-2.8-2.7L7 14.3\"/></svg>\n" +
                "</div>\n" +
                "<div class=\"logo-text\">Texcell LTM 监控管理系统</div>\n" +
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
                "<a href=\"/monitor/queue-monitor\">队列DR监控</a>\n" +
                "<a href=\"/monitor/dr-auto-fix\" class=\"active\">定时任务配置</a>\n" +
                "<a href=\"/monitor/feature-management\">功能模块管理</a>\n" +
                "<a href=\"/monitor/subtask-management\">子任务队列</a>\n" +
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
                "<svg viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><polyline points=\"12 6 12 12 16 14\"/></svg>\n" +
                "客户定时任务配置(DR自动修复)\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"search-card\">\n" +
                "<div class=\"search-row\">\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><path d=\"M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2\"/><circle cx=\"12\" cy=\"7\" r=\"4\"/></svg>客户账号</label><input type=\"text\" id=\"searchSubAccount\" placeholder=\"模糊查询\"></div>\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><path d=\"M12 8v8M8 12h8\"/></svg>状态</label><select id=\"searchStatus\"><option value=\"\">全部</option><option value=\"1\">已启用</option><option value=\"0\">已禁用</option></select></div>\n" +
                "<div class=\"search-actions\">\n" +
                "<button class=\"search-btn\" onclick=\"searchConfigs()\"><svg viewBox=\"0 0 24 24\"><circle cx=\"11\" cy=\"11\" r=\"8\"/><line x1=\"21\" y1=\"21\" x2=\"16.65\" y2=\"16.65\"/></svg>查询</button>\n" +
                "<button class=\"reset-btn\" onclick=\"resetSearch()\"><svg viewBox=\"0 0 24 24\"><polyline points=\"23 4 23 10 17 10\"/><polyline points=\"1 20 1 14 7 14\"/><path d=\"M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15\"/></svg>重置</button>\n" +
                "<button class=\"add-btn\" onclick=\"openAddModal()\"><svg viewBox=\"0 0 24 24\"><line x1=\"12\" y1=\"5\" x2=\"12\" y2=\"19\"/><line x1=\"5\" y1=\"12\" x2=\"19\" y2=\"12\"/></svg>新增配置</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"log-card\">\n" +
                "<div class=\"table-wrapper\">\n" +
                "<table class=\"log-table\">\n" +
                "<thead>\n" +
                "<tr>\n" +
                "<th>客户账号</th>\n" +
                "<th>超时时间(分钟)</th>\n" +
                "<th>最大未回执数</th>\n" +
                "<th>最小发送总数</th>\n" +
                "<th>状态</th>\n" +
                "<th>备注</th>\n" +
                "<th>操作</th>\n" +
                "</tr>\n" +
                "</thead>\n" +
                "<tbody id=\"configBody\"><tr><td colspan=\"7\" class=\"empty\">加载中...</td></tr></tbody>\n" +
                "</table>\n" +
                "</div>\n" +
                "<div class=\"pagination\" id=\"pagination\"></div>\n" +
                "</div>\n" +
                "<div class=\"footer\">客户定时任务配置(DR自动修复) | 配置后客户不参与全局扫描，独立定时任务自动修复 | 每分钟执行一次</div>\n" +
                "</div>\n" +
                "<!-- 新增/编辑弹窗 -->\n" +
                "<div id=\"configModal\" class=\"modal\" style=\"display:none;\">\n" +
                "<div class=\"modal-content\">\n" +
                "<div class=\"modal-header\">\n" +
                "<h3 id=\"modalTitle\"><svg viewBox=\"0 0 24 24\"><line x1=\"12\" y1=\"5\" x2=\"12\" y2=\"19\"/><line x1=\"5\" y1=\"12\" x2=\"19\" y2=\"12\"/></svg>新增配置</h3>\n" +
                "<button class=\"modal-close\" onclick=\"closeModal()\">&times;</button>\n" +
                "</div>\n" +
                "<div class=\"modal-body\">\n" +
                "<input type=\"hidden\" id=\"editId\" value=\"\">\n" +
                "<div class=\"form-group\">\n" +
                "<label>客户账号 <span class=\"required\">*</span></label>\n" +
                "<input type=\"text\" id=\"inputSubAccount\" placeholder=\"请输入客户账号，如 CTU714E\">\n" +
                "</div>\n" +
                "<div class=\"form-row\">\n" +
                "<div class=\"form-group\">\n" +
                "<label>超时时间 <span class=\"required\">*</span></label>\n" +
                "<input type=\"number\" id=\"inputTimeoutMinutes\" value=\"30\" min=\"1\" max=\"999\">\n" +
                "<div class=\"help-text\">超过此时间未回执则自动修复</div>\n" +
                "</div>\n" +
                "<div class=\"form-group\">\n" +
                "<label>最大未回执数</label>\n" +
                "<input type=\"number\" id=\"inputMaxPendingCount\" value=\"10000\" min=\"0\" max=\"999999\">\n" +
                "<div class=\"help-text\">超过此值不自动修复（0表示不限制）</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"form-row\">\n" +
                "<div class=\"form-group\">\n" +
                "<label>最小发送总数</label>\n" +
                "<input type=\"number\" id=\"inputMinTotalCount\" value=\"0\" min=\"0\" max=\"999999\">\n" +
                "<div class=\"help-text\">低于此值不触发自动修复（0表示不限制）</div>\n" +
                "</div>\n" +
                "<div class=\"form-group\">\n" +
                "<label>状态</label>\n" +
                "<select id=\"inputEnabled\"><option value=\"1\">启用</option><option value=\"0\">禁用</option></select>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"form-group\">\n" +
                "<label>备注</label>\n" +
                "<textarea id=\"inputRemark\" rows=\"2\" placeholder=\"备注说明\"></textarea>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"modal-buttons\">\n" +
                "<button class=\"confirm-btn\" onclick=\"saveConfig()\"><svg viewBox=\"0 0 24 24\"><path d=\"M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z\"/><polyline points=\"17 21 17 13 7 13 7 21\"/><polyline points=\"7 3 7 8 15 8\"/></svg>保存</button>\n" +
                "<button class=\"cancel-btn\" onclick=\"closeModal()\">取消</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<script>\n" +
                "var page = 1, size = 20, totalPages = 1;\n" +
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
                "                var roleText = data.data.role === 'ADMIN' ? '管理员' : 'NOC';\n" +
                "                roleSpan.innerText = roleText;\n" +
                "            }\n" +
                "        }).catch(function(err) { console.error('获取用户信息失败:', err); });\n" +
                "}\n" +
                "getCurrentUserInfo();\n" +
                "\n" +
                "function escapeHtml(s){if(!s)return'';return String(s).replace(/[&<>]/g,function(m){if(m==='&')return'&amp;';if(m==='<')return'&lt;';if(m==='>')return'&gt;';return m;});}\n" +
                "\n" +
                "function searchConfigs(){ page = 1; loadConfigs(); }\n" +
                "\n" +
                "function resetSearch(){\n" +
                "    document.getElementById('searchSubAccount').value = '';\n" +
                "    document.getElementById('searchStatus').value = '';\n" +
                "    page = 1;\n" +
                "    loadConfigs();\n" +
                "}\n" +
                "\n" +
                "function loadConfigs(){\n" +
                "    var subAccount = document.getElementById('searchSubAccount').value;\n" +
                "    var status = document.getElementById('searchStatus').value;\n" +
                "    var url = '/api/monitor/dr-auto-fix/list?secret=dn17ORUwf36Znl&pageNum=' + page + '&pageSize=' + size;\n" +
                "    if(subAccount) url += '&subAccount=' + encodeURIComponent(subAccount);\n" +
                "    if(status) url += '&enabled=' + status;\n" +
                "    fetch(url).then(function(res){ return res.json(); })\n" +
                "        .then(function(data){\n" +
                "            if(data.code === 200){\n" +
                "                var records = data.data.records || [];\n" +
                "                totalPages = data.data.totalPages || 1;\n" +
                "                renderTable(records);\n" +
                "                renderPagination();\n" +
                "            }else{\n" +
                "                document.getElementById('configBody').innerHTML = '<tr><td colspan=\"7\" class=\"empty\">加载失败: ' + (data.msg || '错误') + '</td></tr>';\n" +
                "            }\n" +
                "        }).catch(function(){\n" +
                "            document.getElementById('configBody').innerHTML = '<tr><td colspan=\"7\" class=\"empty\">加载失败: 网络错误</td></tr>';\n" +
                "        });\n" +
                "}\n" +
                "\n" +
                "function renderTable(list){\n" +
                "    var tbody = document.getElementById('configBody');\n" +
                "    if(!list || !list.length){\n" +
                "        tbody.innerHTML = '<tr><td colspan=\"7\" class=\"empty\">暂无配置</td></tr>';\n" +
                "        return;\n" +
                "    }\n" +
                "    var editSvg = '<svg viewBox=\"0 0 24 24\"><path d=\"M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7\"/><path d=\"M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z\"/></svg>';\n" +
                "    var pauseSvg = '<svg viewBox=\"0 0 24 24\"><rect x=\"6\" y=\"4\" width=\"4\" height=\"16\"/><rect x=\"14\" y=\"4\" width=\"4\" height=\"16\"/></svg>';\n" +
                "    var playSvg = '<svg viewBox=\"0 0 24 24\"><polygon points=\"5 3 19 12 5 21 5 3\"/></svg>';\n" +
                "    var delSvg = '<svg viewBox=\"0 0 24 24\"><polyline points=\"3 6 5 6 21 6\"/><path d=\"M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2\"/></svg>';\n" +
                "    var html = '';\n" +
                "    for(var i = 0; i < list.length; i++){\n" +
                "        var cfg = list[i];\n" +
                "        var statusHtml = cfg.enabled === 1 ? '<span class=\"status-badge status-enabled\"><span class=\"status-dot\"></span>已启用</span>' : '<span class=\"status-badge status-disabled\"><span class=\"status-dot\"></span>已禁用</span>';\n" +
                "        html += '<tr>';\n" +
                "        html += '<td><strong>' + escapeHtml(cfg.subAccount) + '</strong></td>';\n" +
                "        html += '<td>' + cfg.timeoutMinutes + ' 分钟</td>';\n" +
                "        html += '<td>' + (cfg.maxPendingCount || 0) + '</td>';\n" +
                "        html += '<td>' + (cfg.minTotalCount || 0) + '</td>';\n" +
                "        html += '<td>' + statusHtml + '</td>';\n" +
                "        html += '<td>' + (cfg.remark || '-') + '</td>';\n" +
                "        html += '<td style=\"white-space:nowrap;\">';\n" +
                "        html += '<button class=\"action-btn edit-btn\" onclick=\"editConfig(' + cfg.id + ')\">' + editSvg + '编辑</button>';\n" +
                "        html += '<button class=\"action-btn toggle-btn\" onclick=\"toggleConfig(' + cfg.id + ',' + (cfg.enabled === 1 ? 0 : 1) + ')\">' + (cfg.enabled === 1 ? pauseSvg + '禁用' : playSvg + '启用') + '</button>';\n" +
                "        html += '<button class=\"action-btn delete-btn\" onclick=\"deleteConfig(' + cfg.id + ')\">' + delSvg + '删除</button>';\n" +
                "        html += '</td>';\n" +
                "        html += '</tr>';\n" +
                "    }\n" +
                "    tbody.innerHTML = html;\n" +
                "}\n" +
                "\n" +
                "function renderPagination(){\n" +
                "    var div = document.getElementById('pagination');\n" +
                "    if(!div || totalPages <= 1){ div.innerHTML = ''; return; }\n" +
                "    var html = '<button class=\"page-btn\" onclick=\"goPage(1)\"' + (page === 1 ? 'disabled' : '') + '>首页</button>' +\n" +
                "        '<button class=\"page-btn\" onclick=\"goPage(' + (page - 1) + ')\"' + (page === 1 ? 'disabled' : '') + '>上一页</button>' +\n" +
                "        '<span class=\"page-info\">第 ' + page + ' / ' + totalPages + ' 页</span>' +\n" +
                "        '<button class=\"page-btn\" onclick=\"goPage(' + (page + 1) + ')\"' + (page === totalPages ? 'disabled' : '') + '>下一页</button>' +\n" +
                "        '<button class=\"page-btn\" onclick=\"goPage(' + totalPages + ')\"' + (page === totalPages ? 'disabled' : '') + '>尾页</button>';\n" +
                "    div.innerHTML = html;\n" +
                "}\n" +
                "\n" +
                "function goPage(p){ page = p; loadConfigs(); }\n" +
                "\n" +
                "function openAddModal(){\n" +
                "    document.getElementById('modalTitle').innerHTML = '<svg viewBox=\"0 0 24 24\"><line x1=\"12\" y1=\"5\" x2=\"12\" y2=\"19\"/><line x1=\"5\" y1=\"12\" x2=\"19\" y2=\"12\"/></svg>新增配置';\n" +
                "    document.getElementById('editId').value = '';\n" +
                "    document.getElementById('inputSubAccount').value = '';\n" +
                "    document.getElementById('inputSubAccount').readOnly = false;\n" +
                "    document.getElementById('inputTimeoutMinutes').value = 30;\n" +
                "    document.getElementById('inputMaxPendingCount').value = 10000;\n" +
                "    document.getElementById('inputMinTotalCount').value = 0;\n" +
                "    document.getElementById('inputEnabled').value = 1;\n" +
                "    document.getElementById('inputRemark').value = '';\n" +
                "    document.getElementById('configModal').style.display = 'flex';\n" +
                "}\n" +
                "\n" +
                "function closeModal(){\n" +
                "    document.getElementById('configModal').style.display = 'none';\n" +
                "}\n" +
                "\n" +
                "function editConfig(id){\n" +
                "    fetch('/api/monitor/dr-auto-fix/list?secret=dn17ORUwf36Znl&pageNum=1&pageSize=999')\n" +
                "        .then(function(res){ return res.json(); })\n" +
                "        .then(function(data){\n" +
                "            if(data.code === 200){\n" +
                "                var list = data.data.records || [];\n" +
                "                var cfg = null;\n" +
                "                for(var i = 0; i < list.length; i++){\n" +
                "                    if(list[i].id === id){ cfg = list[i]; break; }\n" +
                "                }\n" +
                "                if(cfg){\n" +
                "                    document.getElementById('modalTitle').innerHTML = '<svg viewBox=\"0 0 24 24\"><path d=\"M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7\"/><path d=\"M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z\"/></svg>编辑配置 - ' + cfg.subAccount;\n" +
                "                    document.getElementById('editId').value = cfg.id;\n" +
                "                    document.getElementById('inputSubAccount').value = cfg.subAccount;\n" +
                "                    document.getElementById('inputSubAccount').readOnly = true;\n" +
                "                    document.getElementById('inputTimeoutMinutes').value = cfg.timeoutMinutes;\n" +
                "                    document.getElementById('inputMaxPendingCount').value = cfg.maxPendingCount || 0;\n" +
                "                    document.getElementById('inputMinTotalCount').value = cfg.minTotalCount || 0;\n" +
                "                    document.getElementById('inputEnabled').value = cfg.enabled;\n" +
                "                    document.getElementById('inputRemark').value = cfg.remark || '';\n" +
                "                    document.getElementById('configModal').style.display = 'flex';\n" +
                "                }\n" +
                "            }\n" +
                "        });\n" +
                "}\n" +
                "\n" +
                "function saveConfig(){\n" +
                "    var id = document.getElementById('editId').value;\n" +
                "    var subAccount = document.getElementById('inputSubAccount').value.trim();\n" +
                "    var timeoutMinutes = parseInt(document.getElementById('inputTimeoutMinutes').value);\n" +
                "    var maxPendingCount = parseInt(document.getElementById('inputMaxPendingCount').value) || 0;\n" +
                "    var minTotalCount = parseInt(document.getElementById('inputMinTotalCount').value) || 0;\n" +
                "    var enabled = parseInt(document.getElementById('inputEnabled').value);\n" +
                "    var remark = document.getElementById('inputRemark').value.trim();\n" +
                "\n" +
                "    if(!subAccount){ alert('请输入客户账号'); return; }\n" +
                "    if(!timeoutMinutes || timeoutMinutes < 1){ alert('请输入有效的超时时间'); return; }\n" +
                "\n" +
                "    var data = {\n" +
                "        id: id ? parseInt(id) : null,\n" +
                "        subAccount: subAccount,\n" +
                "        timeoutMinutes: timeoutMinutes,\n" +
                "        maxPendingCount: maxPendingCount,\n" +
                "        minTotalCount: minTotalCount,\n" +
                "        enabled: enabled,\n" +
                "        remark: remark\n" +
                "    };\n" +
                "\n" +
                "    fetch('/api/monitor/dr-auto-fix/save?secret=dn17ORUwf36Znl', {\n" +
                "        method: 'POST',\n" +
                "        headers: { 'Content-Type': 'application/json' },\n" +
                "        body: JSON.stringify(data)\n" +
                "    }).then(function(res){ return res.json(); })\n" +
                "      .then(function(result){\n" +
                "          if(result.code === 200){\n" +
                "              alert(result.msg);\n" +
                "              closeModal();\n" +
                "              loadConfigs();\n" +
                "          }else{\n" +
                "              alert(result.msg);\n" +
                "          }\n" +
                "      }).catch(function(err){\n" +
                "          alert('网络错误: ' + err.message);\n" +
                "      });\n" +
                "}\n" +
                "\n" +
                "function toggleConfig(id, enabled){\n" +
                "    var action = enabled === 1 ? '启用' : '禁用';\n" +
                "    if(!confirm('确认要' + action + '该配置吗？')) return;\n" +
                "\n" +
                "    fetch('/api/monitor/dr-auto-fix/toggle?id=' + id + '&enabled=' + enabled + '&secret=dn17ORUwf36Znl', {\n" +
                "        method: 'POST'\n" +
                "    }).then(function(res){ return res.json(); })\n" +
                "      .then(function(result){\n" +
                "          alert(result.code === 200 ? result.msg : result.msg);\n" +
                "          if(result.code === 200) loadConfigs();\n" +
                "      });\n" +
                "}\n" +
                "\n" +
                "function deleteConfig(id){\n" +
                "    if(!confirm('确认要删除该配置吗？')) return;\n" +
                "\n" +
                "    fetch('/api/monitor/dr-auto-fix/delete?id=' + id + '&secret=dn17ORUwf36Znl', {\n" +
                "        method: 'POST'\n" +
                "    }).then(function(res){ return res.json(); })\n" +
                "      .then(function(result){\n" +
                "          alert(result.code === 200 ? result.msg : result.msg);\n" +
                "          if(result.code === 200) loadConfigs();\n" +
                "      });\n" +
                "}\n" +
                "\n" +
                "loadConfigs();\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }

    private String generateFeatureManagementHtml() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=no\">\n" +
                "<title>Texcell LTM WEB 客户端功能模块管理</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='22' fill='%231677ff'/%3E%3Ctext x='50' y='72' font-size='60' font-weight='bold' text-anchor='middle' fill='white' font-family='Arial'%3ET%3C/text%3E%3C/svg%3E\">\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:ital,wght@0,300;0,400;0,500;0,600;0,700;1,400&display=swap\" rel=\"stylesheet\">\n" +
                "<style>\n" +
                ":root {\n" +
                "  --primary-color:#1677ff;\n" +
                "  --primary-hover:#3c8dff;\n" +
                "  --text-primary:#1e293b;\n" +
                "  --text-secondary:#475569;\n" +
                "  --text-muted:#8a97a8;\n" +
                "  --input-border:#d9e2ec;\n" +
                "  --bg-page:#f5f7fa;\n" +
                "  --bg-card:#ffffff;\n" +
                "  --border-color:#e8ecf1;\n" +
                "  --shadow-sm:0 1px 3px rgba(0,0,0,0.06);\n" +
                "  --shadow-hover:0 4px 12px rgba(22,119,255,0.1);\n" +
                "  --success-color:#52c41a;\n" +
                "  --danger-color:#ff4d4f;\n" +
                "  --warn-color:#faad14;\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:var(--bg-page);font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;}\n" +
                ".navbar{background:var(--bg-card);box-shadow:var(--shadow-sm);border-bottom:1px solid var(--border-color);position:sticky;top:0;z-index:100;}\n" +
                ".nav-container{max-width:2100px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;padding:0 32px;height:70px;}\n" +
                ".logo{display:flex;align-items:center;gap:12px;}\n" +
                ".logo-icon{width:40px;height:40px;background:linear-gradient(135deg,var(--primary-color) 0%,var(--primary-hover) 100%);border-radius:12px;display:flex;align-items:center;justify-content:center;}\n" +
                ".logo-icon svg{width:22px;height:22px;stroke:#fff;stroke-width:2;fill:none;}\n" +
                ".logo-text{font-size:18px;font-weight:700;color:var(--text-primary);letter-spacing:1px;}\n" +
                ".logo-sub{font-size:11px;color:var(--text-muted);margin-left:8px;}\n" +
                ".menu{display:flex;gap:12px;}\n" +
                ".menu a{color:var(--text-secondary);text-decoration:none;padding:8px 20px;border-radius:40px;transition:all 0.3s;font-weight:500;font-size:14px;}\n" +
                ".menu a:hover{background:#f1f5f9;color:var(--text-primary);}\n" +
                ".menu a.active{background:#e8f0fe;color:var(--primary-color);}\n" +
                ".user-info{display:flex;align-items:center;gap:20px;}\n" +
                ".user-name{color:var(--text-secondary);font-size:14px;}\n" +
                ".user-role{background:rgba(22,119,255,0.1);padding:4px 12px;border-radius:20px;font-size:12px;color:var(--primary-color);}\n" +
                ".logout-btn{background:#f1f5f9;border:1px solid var(--border-color);color:var(--text-secondary);padding:8px 20px;border-radius:40px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".page-wrap{max-width:2100px;margin:0 auto;padding:32px;}\n" +
                ".page-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:28px;flex-wrap:wrap;gap:16px;}\n" +
                ".page-title{font-size:22px;font-weight:700;color:var(--text-primary);display:flex;align-items:center;gap:12px;}\n" +
                ".page-title svg{width:24px;height:24px;stroke:var(--primary-color);stroke-width:2;fill:none;}\n" +
                ".search-card{background:var(--bg-card);border-radius:12px;padding:24px;margin-bottom:32px;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);}\n" +
                ".search-row{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-end;}\n" +
                ".search-group{display:flex;flex-direction:column;gap:6px;}\n" +
                ".search-group label{font-size:12px;color:var(--text-secondary);font-weight:500;display:flex;align-items:center;gap:6px;}\n" +
                ".search-group label svg{width:14px;height:14px;stroke:var(--text-muted);stroke-width:2;fill:none;}\n" +
                ".search-group input,.search-group select{padding:10px 12px;border:1px solid var(--input-border);border-radius:10px;font-size:13px;min-width:170px;background:var(--bg-card);color:var(--text-primary);font-family:inherit;}\n" +
                ".search-group input:focus,.search-group select:focus{outline:none;border-color:var(--primary-color);box-shadow:0 0 0 3px rgba(22,119,255,0.12);}\n" +
                ".search-actions{display:flex;gap:10px;margin-left:auto;align-items:center;flex-wrap:wrap;}\n" +
                ".search-btn{background:var(--primary-color);color:white;border:none;padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;display:inline-flex;align-items:center;gap:6px;}\n" +
                ".search-btn:hover{background:var(--primary-hover);box-shadow:0 8px 20px -6px rgba(22,119,255,0.45);}\n" +
                ".search-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".add-btn{background:var(--success-color);color:white;border:none;padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;display:inline-flex;align-items:center;gap:6px;}\n" +
                ".add-btn:hover{background:#389e0d;box-shadow:0 8px 20px -6px rgba(82,196,26,0.45);}\n" +
                ".add-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".log-card{background:var(--bg-card);border-radius:12px;overflow:hidden;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);}\n" +
                ".table-wrapper{overflow-x:auto;}\n" +
                ".log-table{width:100%;border-collapse:collapse;min-width:900px;}\n" +
                ".log-table th{background:#f8fafc;padding:14px 16px;text-align:left;font-size:13px;font-weight:600;color:var(--text-secondary);border-bottom:1px solid var(--border-color);white-space:nowrap;}\n" +
                ".log-table td{padding:14px 16px;font-size:13px;color:var(--text-primary);border-bottom:1px solid var(--border-color);vertical-align:middle;}\n" +
                ".log-table tr:hover td{background:#f8fafc;}\n" +
                ".status-badge{display:inline-flex;align-items:center;gap:4px;padding:3px 10px;border-radius:20px;font-size:11px;font-weight:500;}\n" +
                ".status-dot{width:6px;height:6px;border-radius:50%;background:currentColor;}\n" +
                ".status-enabled{background:rgba(82,196,26,0.1);color:var(--success-color);}\n" +
                ".status-disabled{background:rgba(255,77,79,0.1);color:var(--danger-color);}\n" +
                ".action-btn{padding:6px 14px;border-radius:40px;cursor:pointer;font-size:12px;transition:all 0.3s;border:none;font-weight:500;display:inline-flex;align-items:center;gap:4px;}\n" +
                ".action-btn svg{width:13px;height:13px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".action-btn.edit-btn{background:#e8f0fe;color:var(--primary-color);}\n" +
                ".action-btn.edit-btn:hover{background:var(--primary-color);color:white;transform:translateY(-1px);}\n" +
                ".action-btn.auth-btn{background:rgba(250,173,20,0.15);color:var(--warn-color);margin-left:6px;}\n" +
                ".action-btn.auth-btn:hover{background:var(--warn-color);color:white;transform:translateY(-1px);}\n" +
                ".action-btn.toggle-btn{background:rgba(82,196,26,0.1);color:var(--success-color);margin-left:6px;}\n" +
                ".action-btn.toggle-btn:hover{background:var(--success-color);color:white;transform:translateY(-1px);}\n" +
                ".action-btn.delete-btn{background:rgba(255,77,79,0.1);color:var(--danger-color);margin-left:6px;}\n" +
                ".action-btn.delete-btn:hover{background:var(--danger-color);color:white;transform:translateY(-1px);}\n" +
                ".empty{text-align:center;padding:48px;color:var(--text-muted);}\n" +
                ".footer{text-align:center;padding:32px 0;color:var(--text-muted);font-size:12px;border-top:1px solid var(--border-color);margin-top:32px;}\n" +
                ".modal{position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(15,32,64,0.45);z-index:10000;display:flex;align-items:center;justify-content:center;backdrop-filter:blur(4px);}\n" +
                ".modal-content{background:var(--bg-card);border-radius:16px;width:600px;max-width:90%;max-height:90vh;overflow-y:auto;padding:28px;box-shadow:0 24px 60px -12px rgba(15,45,105,0.25);border:1px solid var(--border-color);}\n" +
                ".modal-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:24px;padding-bottom:16px;border-bottom:1px solid var(--border-color);}\n" +
                ".modal-header h3{color:var(--text-primary);margin:0;font-size:17px;font-weight:600;display:flex;align-items:center;gap:8px;}\n" +
                ".modal-header h3 svg{width:18px;height:18px;stroke:var(--primary-color);stroke-width:2;fill:none;}\n" +
                ".modal-close{background:none;border:none;font-size:28px;cursor:pointer;color:var(--text-muted);line-height:1;}\n" +
                ".modal-close:hover{color:var(--text-primary);}\n" +
                ".modal-body{color:var(--text-secondary);font-size:14px;}\n" +
                ".modal-buttons{display:flex;gap:12px;justify-content:flex-end;margin-top:24px;padding-top:16px;border-top:1px solid var(--border-color);}\n" +
                ".modal-buttons .confirm-btn{background:var(--primary-color);color:white;border:none;padding:10px 26px;border-radius:10px;cursor:pointer;font-weight:500;font-size:14px;}\n" +
                ".modal-buttons .confirm-btn:hover{background:var(--primary-hover);}\n" +
                ".modal-buttons .cancel-btn{background:#f1f5f9;border:1px solid var(--border-color);padding:10px 26px;border-radius:10px;cursor:pointer;color:var(--text-secondary);font-weight:500;font-size:14px;}\n" +
                ".modal-buttons .cancel-btn:hover{background:#e8ecf1;}\n" +
                ".form-group{margin-bottom:18px;}\n" +
                ".form-group label{display:block;font-size:13px;font-weight:500;color:var(--text-secondary);margin-bottom:7px;}\n" +
                ".form-group label .required{color:var(--danger-color);}\n" +
                ".form-group input,.form-group select,.form-group textarea{width:100%;padding:11px 14px;border:1px solid var(--input-border);border-radius:10px;font-size:14px;background:var(--bg-card);color:var(--text-primary);font-family:inherit;}\n" +
                ".form-group input:focus,.form-group select:focus,.form-group textarea:focus{outline:none;border-color:var(--primary-color);box-shadow:0 0 0 3px rgba(22,119,255,0.12);}\n" +
                ".form-group input[readonly]{background:#f1f5f9;color:var(--text-muted);cursor:not-allowed;}\n" +
                ".form-group .help-text{font-size:11px;color:var(--text-muted);margin-top:6px;}\n" +
                ".form-row{display:grid;grid-template-columns:1fr 1fr;gap:16px;}\n" +
                "@media(max-width:768px){.form-row{grid-template-columns:1fr;}.search-actions{margin-left:0;flex-wrap:wrap;}.nav-container{padding:0 20px;}.menu a{padding:6px 14px;font-size:12px;}.page-wrap{padding:20px;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"navbar\">\n" +
                "<div class=\"nav-container\">\n" +
                "<div class=\"logo\">\n" +
                "<div class=\"logo-icon\"><svg viewBox=\"0 0 24 24\"><path d=\"M3 3v18h18\"/><path d=\"M18.7 8l-5.1 5.2-2.8-2.7L7 14.3\"/></svg></div>\n" +
                "<div class=\"logo-text\">Texcell LTM 监控管理系统</div>\n" +
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
                "<a href=\"/monitor/queue-monitor\">队列DR监控</a>\n" +
                "<a href=\"/monitor/dr-auto-fix\">定时任务配置</a>\n" +
                "<a href=\"/monitor/feature-management\" class=\"active\">功能模块管理</a>\n" +
                "<a href=\"/monitor/subtask-management\">子任务队列</a>\n" +
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
                "<svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"3\" width=\"7\" height=\"7\"/><rect x=\"14\" y=\"3\" width=\"7\" height=\"7\"/><rect x=\"14\" y=\"14\" width=\"7\" height=\"7\"/><rect x=\"3\" y=\"14\" width=\"7\" height=\"7\"/></svg>\n" +
                "客户端功能模块管理\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"search-card\">\n" +
                "<div class=\"search-row\">\n" +
                "<div class=\"search-actions\" style=\"margin-left:0;\">\n" +
                "<button class=\"add-btn\" onclick=\"openModuleModal()\"><svg viewBox=\"0 0 24 24\"><line x1=\"12\" y1=\"5\" x2=\"12\" y2=\"19\"/><line x1=\"5\" y1=\"12\" x2=\"19\" y2=\"12\"/></svg>新增模块</button>\n" +
                "<button class=\"search-btn\" onclick=\"loadModules()\"><svg viewBox=\"0 0 24 24\"><polyline points=\"23 4 23 10 17 10\"/><polyline points=\"1 20 1 14 7 14\"/><path d=\"M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15\"/></svg>刷新</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"log-card\">\n" +
                "<div class=\"table-wrapper\">\n" +
                "<table class=\"log-table\">\n" +
                "<thead>\n" +
                "<tr>\n" +
                "<th>ID</th>\n" +
                "<th>模块编码</th>\n" +
                "<th>模块名称</th>\n" +
                "<th>类型</th>\n" +
                "<th>全局开关</th>\n" +
                "<th>排序</th>\n" +
                "<th>备注</th>\n" +
                "<th>操作</th>\n" +
                "</tr>\n" +
                "</thead>\n" +
                "<tbody id=\"moduleBody\"><tr><td colspan=\"8\" class=\"empty\">加载中...</td></tr></tbody>\n" +
                "</table>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"footer\">客户端功能模块管理 | 新增后需为客户授权 | 支持全局启用/禁用</div>\n" +
                "</div>\n" +
                "<!-- 新增/编辑模块弹窗 -->\n" +
                "<div id=\"moduleModal\" class=\"modal\" style=\"display:none;\">\n" +
                "<div class=\"modal-content\">\n" +
                "<div class=\"modal-header\">\n" +
                "<h3 id=\"moduleModalTitle\"><svg viewBox=\"0 0 24 24\"><line x1=\"12\" y1=\"5\" x2=\"12\" y2=\"19\"/><line x1=\"5\" y1=\"12\" x2=\"19\" y2=\"12\"/></svg>新增模块</h3>\n" +
                "<button class=\"modal-close\" onclick=\"closeModuleModal()\">&times;</button>\n" +
                "</div>\n" +
                "<div class=\"modal-body\">\n" +
                "<input type=\"hidden\" id=\"m_id\" value=\"\">\n" +
                "<div class=\"form-group\">\n" +
                "<label>模块编码 <span class=\"required\">*</span></label>\n" +
                "<input type=\"text\" id=\"m_code\" placeholder=\"如 batch_send_content，唯一不可重复\">\n" +
                "<div class=\"help-text\">前端用它控制 Tab 显示；一旦保存不可修改</div>\n" +
                "</div>\n" +
                "<div class=\"form-group\">\n" +
                "<label>模块名称 <span class=\"required\">*</span></label>\n" +
                "<input type=\"text\" id=\"m_name\" placeholder=\"如 批量发送\">\n" +
                "</div>\n" +
                "<div class=\"form-row\">\n" +
                "<div class=\"form-group\">\n" +
                "<label>类型</label>\n" +
                "<select id=\"m_type\">\n" +
                "<option value=\"TAB\">TAB - 页签</option>\n" +
                "<option value=\"PAGE\">PAGE - 独立页</option>\n" +
                "</select>\n" +
                "</div>\n" +
                "<div class=\"form-group\">\n" +
                "<label>排序</label>\n" +
                "<input type=\"number\" id=\"m_sort\" value=\"0\" min=\"0\" max=\"9999\">\n" +
                "<div class=\"help-text\">数字越小越靠前</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"form-group\">\n" +
                "<label>备注</label>\n" +
                "<textarea id=\"m_remark\" rows=\"2\" placeholder=\"备注说明\"></textarea>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"modal-buttons\">\n" +
                "<button class=\"confirm-btn\" onclick=\"saveModule()\">保存</button>\n" +
                "<button class=\"cancel-btn\" onclick=\"closeModuleModal()\">取消</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<!-- 授权管理弹窗 -->\n" +
                "<div id=\"authModal\" class=\"modal\" style=\"display:none;\">\n" +
                "<div class=\"modal-content\" style=\"width:720px;\">\n" +
                "<div class=\"modal-header\">\n" +
                "<h3 id=\"authModalTitle\"><svg viewBox=\"0 0 24 24\"><path d=\"M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2\"/><circle cx=\"9\" cy=\"7\" r=\"4\"/><path d=\"M23 21v-2a4 4 0 0 0-3-3.87\"/><path d=\"M16 3.13a4 4 0 0 1 0 7.75\"/></svg>客户授权管理</h3>\n" +
                "<button class=\"modal-close\" onclick=\"closeAuthModal()\">&times;</button>\n" +
                "</div>\n" +
                "<div class=\"modal-body\">\n" +
                "<input type=\"hidden\" id=\"auth_module_code\" value=\"\">\n" +
                "<div style=\"display:flex;gap:10px;margin-bottom:16px;\">\n" +
                "<input type=\"text\" id=\"auth_sub_account\" placeholder=\"输入客户账号，如 CTU841E\" style=\"flex:1;padding:11px 14px;border:1px solid var(--input-border);border-radius:10px;font-size:14px;\">\n" +
                "<button class=\"search-btn\" onclick=\"addAuth()\">授权</button>\n" +
                "</div>\n" +
                "<div id=\"authListWrap\" style=\"border:1px solid var(--border-color);border-radius:10px;max-height:400px;overflow-y:auto;\">\n" +
                "<table class=\"log-table\" style=\"min-width:auto;\">\n" +
                "<thead><tr><th>客户账号</th><th>状态</th><th>操作</th></tr></thead>\n" +
                "<tbody id=\"authBody\"><tr><td colspan=\"3\" class=\"empty\">加载中...</td></tr></tbody>\n" +
                "</table>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"modal-buttons\">\n" +
                "<button class=\"cancel-btn\" onclick=\"closeAuthModal()\">关闭</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<script>\n" +
                "var SECRET = 'dn17ORUwf36Znl';\n" +
                "\n" +
                "function logout(){fetch('/monitor/logout').then(function(){location.href='/monitor/login';});}\n" +
                "function getCurrentUserInfo(){fetch('/api/monitor/current-user?secret='+SECRET).then(function(r){return r.json();}).then(function(d){if(d.code===200){document.getElementById('currentUsername').innerText=d.data.username;document.getElementById('currentUserRole').innerText=d.data.role==='ADMIN'?'管理员':'NOC';}});}\n" +
                "getCurrentUserInfo();\n" +
                "\n" +
                "function escapeHtml(s){if(!s)return'';return String(s).replace(/[&<>]/g,function(m){if(m==='&')return'&amp;';if(m==='<')return'&lt;';if(m==='>')return'&gt;';return m;});}\n" +
                "\n" +
                "function loadModules(){\n" +
                "    fetch('/api/monitor/feature/modules?secret='+SECRET)\n" +
                "        .then(function(r){return r.json();})\n" +
                "        .then(function(d){\n" +
                "            var tbody = document.getElementById('moduleBody');\n" +
                "            if(d.code!==200){tbody.innerHTML='<tr><td colspan=\"8\" class=\"empty\">加载失败:'+(d.msg||'错误')+'</td></tr>';return;}\n" +
                "            var list = d.data || [];\n" +
                "            if(!list.length){tbody.innerHTML='<tr><td colspan=\"8\" class=\"empty\">暂无模块</td></tr>';return;}\n" +
                "            var html = '';\n" +
                "            for(var i=0;i<list.length;i++){\n" +
                "                var m = list[i];\n" +
                "                var st = m.enabled === 1 ? '<span class=\"status-badge status-enabled\"><span class=\"status-dot\"></span>启用</span>' : '<span class=\"status-badge status-disabled\"><span class=\"status-dot\"></span>禁用</span>';\n" +
                "                html += '<tr>';\n" +
                "                html += '<td>'+m.id+'</td>';\n" +
                "                html += '<td><code style=\"background:#f1f5f9;padding:3px 10px;border-radius:6px;font-size:12px;\">'+escapeHtml(m.moduleCode)+'</code></td>';\n" +
                "                html += '<td><strong>'+escapeHtml(m.moduleName)+'</strong></td>';\n" +
                "                html += '<td>'+escapeHtml(m.moduleType||'TAB')+'</td>';\n" +
                "                html += '<td>'+st+'</td>';\n" +
                "                html += '<td>'+(m.sortOrder||0)+'</td>';\n" +
                "                html += '<td>'+(escapeHtml(m.remark)||'-')+'</td>';\n" +
                "                html += '<td style=\"white-space:nowrap;\">';\n" +
                "                html += '<button class=\"action-btn edit-btn\" onclick=\"editModule('+m.id+')\">编辑</button>';\n" +
                "                html += '<button class=\"action-btn toggle-btn\" onclick=\"toggleModule('+m.id+','+(m.enabled===1?0:1)+')\">'+(m.enabled===1?'禁用':'启用')+'</button>';\n" +
                "                html += '<button class=\"action-btn auth-btn\" onclick=\"openAuthModal(\\''+escapeHtml(m.moduleCode)+'\\',\\''+escapeHtml(m.moduleName)+'\\')\">授权</button>';\n" +
                "                html += '<button class=\"action-btn delete-btn\" onclick=\"deleteModule('+m.id+')\">删除</button>';\n" +
                "                html += '</td></tr>';\n" +
                "            }\n" +
                "            tbody.innerHTML = html;\n" +
                "        }).catch(function(){document.getElementById('moduleBody').innerHTML='<tr><td colspan=\"8\" class=\"empty\">网络错误</td></tr>';});\n" +
                "}\n" +
                "\n" +
                "var moduleCache = {};\n" +
                "function editModule(id){\n" +
                "    fetch('/api/monitor/feature/modules?secret='+SECRET).then(function(r){return r.json();}).then(function(d){\n" +
                "        if(d.code!==200) return;\n" +
                "        var m = (d.data||[]).filter(function(x){return x.id===id;})[0];\n" +
                "        if(!m) return;\n" +
                "        document.getElementById('moduleModalTitle').innerText = '编辑模块 - ' + m.moduleCode;\n" +
                "        document.getElementById('m_id').value = m.id;\n" +
                "        document.getElementById('m_code').value = m.moduleCode;\n" +
                "        document.getElementById('m_code').readOnly = true;\n" +
                "        document.getElementById('m_name').value = m.moduleName;\n" +
                "        document.getElementById('m_type').value = m.moduleType || 'TAB';\n" +
                "        document.getElementById('m_sort').value = m.sortOrder || 0;\n" +
                "        document.getElementById('m_remark').value = m.remark || '';\n" +
                "        document.getElementById('moduleModal').style.display = 'flex';\n" +
                "    });\n" +
                "}\n" +
                "\n" +
                "function openModuleModal(){\n" +
                "    document.getElementById('moduleModalTitle').innerText = '新增模块';\n" +
                "    document.getElementById('m_id').value = '';\n" +
                "    document.getElementById('m_code').value = '';\n" +
                "    document.getElementById('m_code').readOnly = false;\n" +
                "    document.getElementById('m_name').value = '';\n" +
                "    document.getElementById('m_type').value = 'TAB';\n" +
                "    document.getElementById('m_sort').value = 0;\n" +
                "    document.getElementById('m_remark').value = '';\n" +
                "    document.getElementById('moduleModal').style.display = 'flex';\n" +
                "}\n" +
                "function closeModuleModal(){document.getElementById('moduleModal').style.display='none';}\n" +
                "\n" +
                "function saveModule(){\n" +
                "    var id = document.getElementById('m_id').value;\n" +
                "    var code = document.getElementById('m_code').value.trim();\n" +
                "    var name = document.getElementById('m_name').value.trim();\n" +
                "    if(!code){alert('请输入模块编码');return;}\n" +
                "    if(!name){alert('请输入模块名称');return;}\n" +
                "    var data = {\n" +
                "        id: id ? parseInt(id) : null,\n" +
                "        moduleCode: code,\n" +
                "        moduleName: name,\n" +
                "        moduleType: document.getElementById('m_type').value,\n" +
                "        enabled: 1,\n" +
                "        sortOrder: parseInt(document.getElementById('m_sort').value) || 0,\n" +
                "        remark: document.getElementById('m_remark').value\n" +
                "    };\n" +
                "    fetch('/api/monitor/feature/module/save?secret='+SECRET, {\n" +
                "        method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(data)\n" +
                "    }).then(function(r){return r.json();}).then(function(d){\n" +
                "        if(d.code===200){alert('保存成功');closeModuleModal();loadModules();}\n" +
                "        else alert('保存失败: '+(d.msg||''));\n" +
                "    });\n" +
                "}\n" +
                "\n" +
                "function toggleModule(id, enabled){\n" +
                "    if(!confirm('确认要'+(enabled?'启用':'禁用')+'该模块吗？')) return;\n" +
                "    fetch('/api/monitor/feature/module/toggle?secret='+SECRET+'&id='+id+'&enabled='+enabled, {method:'POST'})\n" +
                "        .then(function(r){return r.json();})\n" +
                "        .then(function(d){alert(d.code===200?'操作成功':'操作失败');if(d.code===200) loadModules();});\n" +
                "}\n" +
                "\n" +
                "function deleteModule(id){\n" +
                "    if(!confirm('确认要删除该模块吗？删除后客户将不可见')) return;\n" +
                "    fetch('/api/monitor/feature/module/delete?secret='+SECRET+'&id='+id, {method:'POST'})\n" +
                "        .then(function(r){return r.json();})\n" +
                "        .then(function(d){alert(d.code===200?'删除成功':'删除失败');if(d.code===200) loadModules();});\n" +
                "}\n" +
                "\n" +
                "var currentAuthCode = '';\n" +
                "function openAuthModal(moduleCode, moduleName){\n" +
                "    currentAuthCode = moduleCode;\n" +
                "    document.getElementById('authModalTitle').innerHTML = '<svg viewBox=\"0 0 24 24\"><path d=\"M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2\"/><circle cx=\"9\" cy=\"7\" r=\"4\"/></svg> 客户授权 - ' + moduleName + ' (' + moduleCode + ')';\n" +
                "    document.getElementById('auth_module_code').value = moduleCode;\n" +
                "    document.getElementById('auth_sub_account').value = '';\n" +
                "    document.getElementById('authModal').style.display = 'flex';\n" +
                "    loadAuthList();\n" +
                "}\n" +
                "function closeAuthModal(){document.getElementById('authModal').style.display='none';}\n" +
                "\n" +
                "function loadAuthList(){\n" +
                "    var tbody = document.getElementById('authBody');\n" +
                "    tbody.innerHTML = '<tr><td colspan=\"3\" class=\"empty\">加载中...</td></tr>';\n" +
                "    fetch('/api/monitor/feature/module/customers?secret='+SECRET+'&moduleCode='+encodeURIComponent(currentAuthCode))\n" +
                "        .then(function(r){return r.json();})\n" +
                "        .then(function(d){\n" +
                "            if(d.code!==200){tbody.innerHTML='<tr><td colspan=\"3\" class=\"empty\">加载失败</td></tr>';return;}\n" +
                "            var list = d.data || [];\n" +
                "            if(!list.length){tbody.innerHTML='<tr><td colspan=\"3\" class=\"empty\">暂无授权客户</td></tr>';return;}\n" +
                "            var html = '';\n" +
                "            for(var i=0;i<list.length;i++){\n" +
                "                var a = list[i];\n" +
                "                var st = a.enabled===1?'<span class=\"status-badge status-enabled\"><span class=\"status-dot\"></span>启用</span>':'<span class=\"status-badge status-disabled\"><span class=\"status-dot\"></span>禁用</span>';\n" +
                "                html += '<tr><td><strong>'+escapeHtml(a.subAccount)+'</strong></td><td>'+st+'</td><td>';\n" +
                "                html += '<button class=\"action-btn toggle-btn\" onclick=\"toggleAuth(\\''+escapeHtml(a.subAccount)+'\\','+(a.enabled===1?0:1)+')\">'+(a.enabled===1?'禁用':'启用')+'</button>';\n" +
                "                html += '<button class=\"action-btn delete-btn\" onclick=\"deleteAuth(\\''+escapeHtml(a.subAccount)+'\\')\">移除</button>';\n" +
                "                html += '</td></tr>';\n" +
                "            }\n" +
                "            tbody.innerHTML = html;\n" +
                "        });\n" +
                "}\n" +
                "\n" +
                "function addAuth(){\n" +
                "    var acc = document.getElementById('auth_sub_account').value.trim();\n" +
                "    if(!acc){alert('请输入客户账号');return;}\n" +
                "    fetch('/api/monitor/feature/auth/save?secret='+SECRET+'&subAccount='+encodeURIComponent(acc)+'&moduleCode='+encodeURIComponent(currentAuthCode)+'&enabled=1', {method:'POST'})\n" +
                "        .then(function(r){return r.json();})\n" +
                "        .then(function(d){\n" +
                "            if(d.code===200){document.getElementById('auth_sub_account').value='';loadAuthList();}\n" +
                "            else alert('授权失败: '+(d.msg||''));\n" +
                "        });\n" +
                "}\n" +
                "\n" +
                "function toggleAuth(acc, enabled){\n" +
                "    fetch('/api/monitor/feature/auth/save?secret='+SECRET+'&subAccount='+encodeURIComponent(acc)+'&moduleCode='+encodeURIComponent(currentAuthCode)+'&enabled='+enabled, {method:'POST'})\n" +
                "        .then(function(r){return r.json();})\n" +
                "        .then(function(d){if(d.code===200) loadAuthList();else alert('操作失败');});\n" +
                "}\n" +
                "\n" +
                "function deleteAuth(acc){\n" +
                "    if(!confirm('确认要移除客户 '+acc+' 的授权吗？')) return;\n" +
                "    fetch('/api/monitor/feature/auth/delete?secret='+SECRET+'&subAccount='+encodeURIComponent(acc)+'&moduleCode='+encodeURIComponent(currentAuthCode), {method:'POST'})\n" +
                "        .then(function(r){return r.json();})\n" +
                "        .then(function(d){if(d.code===200) loadAuthList();else alert('移除失败');});\n" +
                "}\n" +
                "\n" +
                "loadModules();\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }

    private String generateSubTaskManagementHtml() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=no\">\n" +
                "<title>Texcell LTM WEB 发送子任务管理</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='22' fill='%231677ff'/%3E%3Ctext x='50' y='72' font-size='60' font-weight='bold' text-anchor='middle' fill='white' font-family='Arial'%3ET%3C/text%3E%3C/svg%3E\">\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:ital,wght@0,300;0,400;0,500;0,600;0,700;1,400&display=swap\" rel=\"stylesheet\">\n" +
                "<style>\n" +
                ":root {\n" +
                "  --primary-color:#1677ff; --primary-hover:#3c8dff; --text-primary:#1e293b; --text-secondary:#475569; --text-muted:#8a97a8; --input-border:#d9e2ec;\n" +
                "  --bg-page:#f5f7fa; --bg-card:#ffffff; --border-color:#e8ecf1; --shadow-sm:0 1px 3px rgba(0,0,0,0.06); --shadow-hover:0 4px 12px rgba(22,119,255,0.1);\n" +
                "  --success-color:#52c41a; --danger-color:#ff4d4f; --warn-color:#faad14;\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:var(--bg-page);font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;}\n" +
                ".navbar{background:var(--bg-card);box-shadow:var(--shadow-sm);border-bottom:1px solid var(--border-color);position:sticky;top:0;z-index:100;}\n" +
                ".nav-container{max-width:2100px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;padding:0 32px;height:70px;}\n" +
                ".logo{display:flex;align-items:center;gap:12px;}\n" +
                ".logo-icon{width:40px;height:40px;background:linear-gradient(135deg,var(--primary-color) 0%,var(--primary-hover) 100%);border-radius:12px;display:flex;align-items:center;justify-content:center;}\n" +
                ".logo-icon svg{width:22px;height:22px;stroke:#fff;stroke-width:2;fill:none;}\n" +
                ".logo-text{font-size:18px;font-weight:700;color:var(--text-primary);letter-spacing:1px;}\n" +
                ".logo-sub{font-size:11px;color:var(--text-muted);margin-left:8px;}\n" +
                ".menu{display:flex;gap:12px;}\n" +
                ".menu a{color:var(--text-secondary);text-decoration:none;padding:8px 20px;border-radius:40px;transition:all 0.3s;font-weight:500;font-size:14px;}\n" +
                ".menu a:hover{background:#f1f5f9;color:var(--text-primary);}\n" +
                ".menu a.active{background:#e8f0fe;color:var(--primary-color);box-shadow:none;}\n" +
                ".user-info{display:flex;align-items:center;gap:20px;}\n" +
                ".user-name{color:var(--text-secondary);font-size:14px;}\n" +
                ".user-role{background:rgba(22,119,255,0.1);padding:4px 12px;border-radius:20px;font-size:12px;color:var(--primary-color);}\n" +
                ".logout-btn{background:#f1f5f9;border:1px solid var(--border-color);color:var(--text-secondary);padding:8px 20px;border-radius:40px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".page-wrap{max-width:2100px;margin:0 auto;padding:32px;}\n" +
                ".page-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:28px;flex-wrap:wrap;gap:16px;}\n" +
                ".page-title{font-size:22px;font-weight:700;color:var(--text-primary);display:flex;align-items:center;gap:12px;}\n" +
                ".page-title svg{width:24px;height:24px;stroke:var(--primary-color);stroke-width:2;fill:none;}\n" +
                ".search-card{background:var(--bg-card);border-radius:12px;padding:24px;margin-bottom:32px;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);}\n" +
                ".search-row{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-end;}\n" +
                ".search-group{display:flex;flex-direction:column;gap:6px;}\n" +
                ".search-group label{font-size:12px;color:var(--text-secondary);font-weight:500;display:flex;align-items:center;gap:6px;}\n" +
                ".search-group label svg{width:14px;height:14px;stroke:var(--text-muted);stroke-width:2;fill:none;}\n" +
                ".search-group input,.search-group select{padding:10px 12px;border:1px solid var(--input-border);border-radius:10px;font-size:13px;min-width:170px;background:var(--bg-card);color:var(--text-primary);font-family:inherit;transition:all 0.3s;}\n" +
                ".search-group input:focus,.search-group select:focus{outline:none;border-color:var(--primary-color);box-shadow:0 0 0 3px rgba(22,119,255,0.12);}\n" +
                ".search-actions{display:flex;gap:10px;margin-left:auto;align-items:center;flex-wrap:wrap;}\n" +
                ".search-btn{background:var(--primary-color);color:white;border:none;padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;display:inline-flex;align-items:center;gap:6px;}\n" +
                ".search-btn:hover{background:var(--primary-hover);box-shadow:0 8px 20px -6px rgba(22,119,255,0.45);}\n" +
                ".search-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".reset-btn{background:#f1f5f9;border:1px solid var(--border-color);padding:10px 22px;border-radius:10px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;color:var(--text-secondary);display:inline-flex;align-items:center;gap:6px;}\n" +
                ".reset-btn:hover{background:#e8ecf1;transform:translateY(-1px);}\n" +
                ".reset-btn svg{width:15px;height:15px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".log-card{background:var(--bg-card);border-radius:12px;overflow:hidden;border:1px solid var(--border-color);box-shadow:var(--shadow-sm);}\n" +
                ".table-wrapper{overflow-x:auto;}\n" +
                ".log-table{width:100%;border-collapse:collapse;min-width:1000px;}\n" +
                ".log-table th{background:#f8fafc;padding:14px 16px;text-align:left;font-size:13px;font-weight:600;color:var(--text-secondary);border-bottom:1px solid var(--border-color);white-space:nowrap;}\n" +
                ".log-table td{padding:14px 16px;font-size:13px;color:var(--text-primary);border-bottom:1px solid var(--border-color);vertical-align:middle;}\n" +
                ".log-table tr:hover td{background:#f8fafc;}\n" +
                ".status-badge{display:inline-flex;align-items:center;gap:4px;padding:3px 10px;border-radius:20px;font-size:11px;font-weight:500;}\n" +
                ".status-dot{width:6px;height:6px;border-radius:50%;background:currentColor;}\n" +
                ".status-pending{background:rgba(250,173,20,0.15);color:var(--warn-color);}\n" +
                ".status-processed{background:rgba(22,119,255,0.15);color:var(--primary-color);}\n" +
                ".status-paused{background:rgba(148,163,184,0.15);color:#94a3b8;}\n" +
                ".status-canceled{background:rgba(255,77,79,0.1);color:var(--danger-color);}\n" +
                ".action-btn{padding:6px 14px;border-radius:40px;cursor:pointer;font-size:12px;transition:all 0.3s;border:none;font-weight:500;display:inline-flex;align-items:center;gap:4px;}\n" +
                ".action-btn svg{width:13px;height:13px;stroke:currentColor;stroke-width:2;fill:none;}\n" +
                ".action-btn.pause-btn{background:rgba(250,173,20,0.15);color:var(--warn-color);margin-right:6px;}\n" +
                ".action-btn.pause-btn:hover{background:var(--warn-color);color:white;transform:translateY(-1px);}\n" +
                ".action-btn.resume-btn{background:rgba(82,196,26,0.1);color:var(--success-color);margin-right:6px;}\n" +
                ".action-btn.resume-btn:hover{background:var(--success-color);color:white;transform:translateY(-1px);}\n" +
                ".action-btn.cancel-btn{background:rgba(255,77,79,0.1);color:var(--danger-color);}\n" +
                ".action-btn.cancel-btn:hover{background:var(--danger-color);color:white;transform:translateY(-1px);}\n" +
                ".footer{text-align:center;padding:32px 0;color:var(--text-muted);font-size:12px;border-top:1px solid var(--border-color);margin-top:32px;}\n" +
                ".empty{text-align:center;padding:48px;color:var(--text-muted);}\n" +
                "@media(max-width:768px){.nav-container{padding:0 20px;}.menu a{padding:6px 14px;font-size:12px;}.search-group input,.search-group select{min-width:100%;width:100%;}.search-actions{flex-wrap:wrap;}.page-wrap{padding:20px;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"navbar\">\n" +
                "<div class=\"nav-container\">\n" +
                "<div class=\"logo\">\n" +
                "<div class=\"logo-icon\">\n" +
                "<svg viewBox=\"0 0 24 24\"><path d=\"M3 3v18h18\"/><path d=\"M18.7 8l-5.1 5.2-2.8-2.7L7 14.3\"/></svg>\n" +
                "</div>\n" +
                "<div class=\"logo-text\">Texcell LTM 监控管理系统</div>\n" +
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
                "<a href=\"/monitor/queue-monitor\">队列DR监控</a>\n" +
                "<a href=\"/monitor/dr-auto-fix\">定时任务配置</a>\n" +
                "<a href=\"/monitor/feature-management\">功能模块管理</a>\n" +
                "<a href=\"/monitor/subtask-management\" class=\"active\">子任务队列</a>\n" +
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
                "<svg viewBox=\"0 0 24 24\"><path d=\"M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z\"/><polyline points=\"22,6 12,13 2,6\"/></svg>\n" +
                "发送子任务管理\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"search-card\">\n" +
                "<div class=\"search-row\">\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><path d=\"M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2\"/><circle cx=\"12\" cy=\"7\" r=\"4\"/></svg>客户账号</label><input type=\"text\" id=\"searchSubAccount\" placeholder=\"支持模糊查询\"></div>\n" +
                "<div class=\"search-group\"><label><svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"3\" width=\"18\" height=\"18\" rx=\"2\"/><path d=\"M9 9h6v6H9z\"/></svg>父任务ID</label><input type=\"text\" id=\"searchTaskId\" placeholder=\"精确查询\"></div>\n" +
                "<div class=\"search-actions\">\n" +
                "<button class=\"search-btn\" onclick=\"searchTasks()\"><svg viewBox=\"0 0 24 24\"><circle cx=\"11\" cy=\"11\" r=\"8\"/><line x1=\"21\" y1=\"21\" x2=\"16.65\" y2=\"16.65\"/></svg>查询</button>\n" +
                "<button class=\"reset-btn\" onclick=\"resetSearch()\"><svg viewBox=\"0 0 24 24\"><polyline points=\"23 4 23 10 17 10\"/><polyline points=\"1 20 1 14 7 14\"/><path d=\"M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15\"/></svg>重置</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"log-card\">\n" +
                "<div class=\"table-wrapper\">\n" +
                "<table class=\"log-table\">\n" +
                "<thead>\n" +
                "<tr>\n" +
                "<th>父任务ID</th>\n" +
                "<th>客户账号</th>\n" +
                "<th>子任务序号</th>\n" +
                "<th>号码数</th>\n" +
                "<th>计划发送时间</th>\n" +
                "<th>状态</th>\n" +
                "<th>备注</th>\n" +
                "<th>操作</th>\n" +
                "</tr>\n" +
                "</thead>\n" +
                "<tbody id=\"logBody\"><tr><td colspan=\"8\" class=\"empty\">加载中...</td></tr></tbody>\n" +
                "</table>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"footer\">发送子任务管理 | 最多展示 500 条记录 | 请使用任务 ID 精确查询</div>\n" +
                "</div>\n" +
                "<script>\n" +
                "var SECRET = 'dn17ORUwf36Znl';\n" +
                "\n" +
                "function logout(){ fetch('/monitor/logout').then(function(){ location.href = '/monitor/login'; }); }\n" +
                "function getCurrentUserInfo() {\n" +
                "    fetch('/api/monitor/current-user?secret='+SECRET)\n" +
                "        .then(function(res) { return res.json(); })\n" +
                "        .then(function(data) {\n" +
                "            if (data.code === 200) {\n" +
                "                document.getElementById('currentUsername').innerText = data.data.username;\n" +
                "                var roleSpan = document.getElementById('currentUserRole');\n" +
                "                roleSpan.innerText = data.data.role === 'ADMIN' ? '管理员' : 'NOC';\n" +
                "            }\n" +
                "        });\n" +
                "}\n" +
                "getCurrentUserInfo();\n" +
                "\n" +
                "function escapeHtml(s){if(!s)return'';return String(s).replace(/[&<>]/g,function(m){if(m==='&')return'&amp;';if(m==='<')return'&lt;';if(m==='>')return'&gt;';return m;});}\n" +
                "\n" +
                "function searchTasks(){ loadLogs(); }\n" +
                "function resetSearch(){\n" +
                "    document.getElementById('searchSubAccount').value = '';\n" +
                "    document.getElementById('searchTaskId').value = '';\n" +
                "    loadLogs();\n" +
                "}\n" +
                "\n" +
                "function loadLogs(){\n" +
                "    var subAccount = document.getElementById('searchSubAccount').value;\n" +
                "    var taskId = document.getElementById('searchTaskId').value;\n" +
                "    var url = '/api/monitor/subtask/list?secret=' + SECRET + '&limit=500';\n" +
                "    if(subAccount) url += '&subAccount=' + encodeURIComponent(subAccount);\n" +
                "    if(taskId) url += '&taskId=' + encodeURIComponent(taskId);\n" +
                "    \n" +
                "    document.getElementById('logBody').innerHTML = '<tr><td colspan=\"8\" class=\"empty\">加载中...</td></tr>';\n" +
                "    \n" +
                "    fetch(url).then(function(res){ return res.json(); })\n" +
                "        .then(function(data){\n" +
                "            if(data.code === 200){\n" +
                "                renderTable(data.data || []);\n" +
                "            }else{\n" +
                "                document.getElementById('logBody').innerHTML = '<tr><td colspan=\"8\" class=\"empty\">加载失败: ' + (data.msg || '错误') + '</td></tr>';\n" +
                "            }\n" +
                "        }).catch(function(){\n" +
                "            document.getElementById('logBody').innerHTML = '<tr><td colspan=\"8\" class=\"empty\">加载失败: 网络错误</td></tr>';\n" +
                "        });\n" +
                "}\n" +
                "\n" +
                "function renderTable(list){\n" +
                "    var tbody = document.getElementById('logBody');\n" +
                "    if(!list || !list.length){\n" +
                "        tbody.innerHTML = '<tr><td colspan=\"8\" class=\"empty\">暂无记录</td></tr>';\n" +
                "        return;\n" +
                "    }\n" +
                "    var html = '';\n" +
                "    for(var i = 0; i < list.length; i++){\n" +
                "        var item = list[i];\n" +
                "        \n" +
                "        // 注意：这里的字段名要对应底层存储过程返回的 Map Key\n" +
                "        var status = parseInt(item.SubStatus);\n" +
                "        var statusHtml = '';\n" +
                "        if(status === 0) statusHtml = '<span class=\"status-badge status-pending\"><span class=\"status-dot\"></span>待发送</span>';\n" +
                "        else if(status === 1) statusHtml = '<span class=\"status-badge status-processed\"><span class=\"status-dot\"></span>发送中</span>';\n" +
                "        else if(status === 2) statusHtml = '<span class=\"status-badge status-paused\"><span class=\"status-dot\"></span>暂停</span>';\n" +
                "        else if(status === 3) statusHtml = '<span class=\"status-badge status-canceled\"><span class=\"status-dot\"></span>取消</span>';\n" +
                "        \n" +
                "        var actionHtml = '-';\n" +
                "        if(status === 0){\n" +
                "            actionHtml = '<button class=\"action-btn pause-btn\" onclick=\"operate(\\''+item.APITaskId+'\\','+item.SubIndex+',\\'pause\\')\"><svg viewBox=\"0 0 24 24\"><rect x=\"6\" y=\"4\" width=\"4\" height=\"16\"/><rect x=\"14\" y=\"4\" width=\"4\" height=\"16\"/></svg>暂停</button>' +\n" +
                "                         '<button class=\"action-btn cancel-btn\" onclick=\"operate(\\''+item.APITaskId+'\\','+item.SubIndex+',\\'cancel\\')\"><svg viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><line x1=\"15\" y1=\"9\" x2=\"9\" y2=\"15\"/><line x1=\"9\" y1=\"9\" x2=\"15\" y2=\"15\"/></svg>取消</button>';\n" +
                "        } else if(status === 2){\n" +
                "            actionHtml = '<button class=\"action-btn resume-btn\" onclick=\"operate(\\''+item.APITaskId+'\\','+item.SubIndex+',\\'resume\\')\"><svg viewBox=\"0 0 24 24\"><polygon points=\"5 3 19 12 5 21 5 3\"/></svg>恢复</button>' +\n" +
                "                         '<button class=\"action-btn cancel-btn\" onclick=\"operate(\\''+item.APITaskId+'\\','+item.SubIndex+',\\'cancel\\')\"><svg viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><line x1=\"15\" y1=\"9\" x2=\"9\" y2=\"15\"/><line x1=\"9\" y1=\"9\" x2=\"15\" y2=\"15\"/></svg>取消</button>';\n" +
                "        }\n" +
                "\n" +
                "        html += '<tr>';\n" +
                "        html += '<td style=\"font-family:monospace;font-size:12px;color:#475569;\">' + escapeHtml(item.APITaskId) + '</td>';\n" +
                "        html += '<td>' + escapeHtml(item.SubAccount) + '</td>';\n" +
                "        html += '<td>' + (item.SubIndex || '0') + ' / ' + (item.SubTotal || '0') + '</td>';\n" +
                "        html += '<td>' + (item.CalleeCount || '0') + '</td>';\n" +
                "        html += '<td>' + (item.PlanSendTime || '-') + '</td>';\n" +
                "        html += '<td>' + statusHtml + '</td>';\n" +
                "        html += '<td>' + (item.Remark || '-') + '</td>';\n" +
                "        html += '<td style=\"white-space:nowrap;\">' + actionHtml + '</td>';\n" +
                "        html += '</tr>';\n" +
                "    }\n" +
                "    tbody.innerHTML = html;\n" +
                "}\n" +
                "\n" +
                "function operate(taskId, subIndex, action){\n" +
                "    var names = { pause: '暂停', resume: '恢复', cancel: '取消' };\n" +
                "    if(!confirm('确定要' + names[action] + '该子任务吗？')) return;\n" +
                "    \n" +
                "    var url = '/api/monitor/subtask/operate?secret=' + SECRET + '&action=' + action + '&taskId=' + encodeURIComponent(taskId) + '&subIndex=' + subIndex;\n" +
                "    fetch(url, { method: 'POST' })\n" +
                "        .then(function(res){ return res.json(); })\n" +
                "        .then(function(data){\n" +
                "            if(data.code === 200){\n" +
                "                alert(data.msg);\n" +
                "                loadLogs();\n" +
                "            }else{\n" +
                "                alert('操作失败: ' + (data.msg || ''));\n" +
                "            }\n" +
                "        }).catch(function(){\n" +
                "            alert('网络请求失败');\n" +
                "        });\n" +
                "}\n" +
                "\n" +
                "loadLogs();\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }
}