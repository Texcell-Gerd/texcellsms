package com.texcellsms.sms.monitor;

import com.texcellsms.sms.interceptor.SecurityMonitorInterceptor;
import com.texcellsms.sms.mapper.webportal.LoginAuditLogMapper;
import com.texcellsms.sms.model.entity.LoginAuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
public class LogMonitorController {

    private static final Logger logger = LoggerFactory.getLogger(LogMonitorController.class);
    private static final String MONITOR_SECRET = "dn17ORUwf36Znl";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String ADMIN_USERNAME = "Administrator";
    private static final String ADMIN_PASSWORD = "dn17ORUwf36Znl";

    private static final String[] INNER_IPS = {
            "127.0.0.1", "192.168.", "10.",
            "14.127.51.68", "202.77.56.146"
    };

    @Autowired
    private LoginAuditLogMapper loginAuditLogMapper;

    @Autowired
    private LogMonitorTask logMonitorTask;

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

    private boolean isInnerIp(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        for (String prefix : INNER_IPS) {
            if (ip.startsWith(prefix)) return true;
        }
        return false;
    }

    private boolean isLogin(HttpSession session) {
        return session != null && Boolean.TRUE.equals(session.getAttribute("MONITOR_LOGIN"));
    }

    @GetMapping("/monitor/login")
    public String loginPage(HttpServletRequest request) {
        String clientIp = getRealIp(request);
        if (!isInnerIp(clientIp)) {
            return "<h1 style='color:red;text-align:center;margin-top:100px;'>❌ 仅允许内网访问<br/>您的IP：" + clientIp + "</h1>";
        }
        return generateLoginHtml();
    }

    @PostMapping("/monitor/login")
    public Map<String, Object> doLogin(@RequestParam String username, @RequestParam String password, HttpSession session) {
        Map<String, Object> res = new HashMap<>();
        if (ADMIN_USERNAME.equals(username) && ADMIN_PASSWORD.equals(password)) {
            session.setAttribute("MONITOR_LOGIN", true);
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
        String clientIp = getRealIp(request);
        if (!isInnerIp(clientIp)) {
            return "<h1 style='color:red;text-align:center;margin-top:100px;'>❌ 仅允许内网访问<br/>您的IP：" + clientIp + "</h1>";
        }
        if (!isLogin(request.getSession())) {
            return "<script>location.href='/monitor/login';</script>";
        }
        return generateIndexHtml();
    }

    @GetMapping("/monitor/dashboard")
    public String dashboard(HttpServletRequest request) {
        String clientIp = getRealIp(request);
        if (!isInnerIp(clientIp)) {
            return "<h1 style='color:red;text-align:center;margin-top:100px;'>❌ 仅允许内网访问<br/>您的IP：" + clientIp + "</h1>";
        }
        if (!isLogin(request.getSession())) {
            return "<script>location.href='/monitor/login';</script>";
        }
        return generateDashboardHtml();
    }

    @GetMapping("/monitor/login-audit")
    public String loginAudit(HttpServletRequest request) {
        String clientIp = getRealIp(request);
        if (!isInnerIp(clientIp)) {
            return "<h1 style='color:red;text-align:center;margin-top:100px;'>❌ 仅允许内网访问<br/>您的IP：" + clientIp + "</h1>";
        }
        if (!isLogin(request.getSession())) {
            return "<script>location.href='/monitor/login';</script>";
        }
        return generateLoginAuditHtml();
    }

    @GetMapping("/api/monitor/logs")
    public Map<String, Object> getLogMonitorData(@RequestParam(required = false) String secret, HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        String clientIp = getRealIp(request);

        if (!isInnerIp(clientIp)) {
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
        String clientIp = getRealIp(request);

        if (!isInnerIp(clientIp)) {
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
        String clientIp = getRealIp(request);

        if (!isInnerIp(clientIp)) {
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
        String clientIp = getRealIp(request);

        if (!isInnerIp(clientIp)) {
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

    // 在 LogMonitorController 中添加以下方法

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

        String clientIp = getRealIp(request);

        if (!isInnerIp(clientIp)) {
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
                "<title>Texcell 预付WEB 监控系统登录</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='20' fill='%23667eea'/%3E%3Ctext x='50' y='68' font-size='50' text-anchor='middle' fill='white' font-family='Arial'%3E📊%3C/text%3E%3C/svg%3E\">\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:ital,wght@0,300;0,400;0,500;0,600;0,700;1,400&display=swap\" rel=\"stylesheet\">\n" +
                "<style>\n" +
                ":root {\n" +
                "  --bg-overlay: rgba(0,0,0,0.4);\n" +
                "  --card-bg: rgba(255,255,255,0.98);\n" +
                "  --text-primary: #1e293b;\n" +
                "  --text-secondary: #475569;\n" +
                "  --text-muted: #94a3b8;\n" +
                "  --input-border: #e2e8f0;\n" +
                "  --input-bg: #ffffff;\n" +
                "  --error-bg: #fef2f2;\n" +
                "  --error-border: #fecaca;\n" +
                "  --error-text: #dc2626;\n" +
                "}\n" +
                "[data-theme=\"dark\"] {\n" +
                "  --card-bg: rgba(30,41,59,0.98);\n" +
                "  --text-primary: #f1f5f9;\n" +
                "  --text-secondary: #cbd5e1;\n" +
                "  --text-muted: #64748b;\n" +
                "  --input-border: #334155;\n" +
                "  --input-bg: #1e293b;\n" +
                "  --error-bg: rgba(127,29,29,0.4);\n" +
                "  --error-border: #7f1d1d;\n" +
                "  --error-text: #f87171;\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:linear-gradient(135deg,#0f172a 0%,#1e1b4b 50%,#2e1065 100%);min-height:100vh;display:flex;justify-content:center;align-items:center;position:relative;overflow:hidden;transition:background 0.3s;}\n" +
                "body::before{content:'';position:absolute;inset:0;background:var(--bg-overlay);pointer-events:none;}\n" +
                ".bg-orb{position:absolute;width:500px;height:500px;border-radius:50%;filter:blur(80px);opacity:0.3;animation:float 20s infinite ease-in-out;}\n" +
                ".orb-1{background:#667eea;top:-150px;right:-150px;animation-delay:0s;}\n" +
                ".orb-2{background:#764ba2;bottom:-150px;left:-150px;animation-delay:5s;}\n" +
                ".orb-3{background:#f59e0b;top:50%;left:50%;transform:translate(-50%,-50%);width:400px;height:400px;opacity:0.15;animation-delay:10s;}\n" +
                "@keyframes float{0%,100%{transform:translate(0,0) scale(1);}33%{transform:translate(30px,-30px) scale(1.05);}66%{transform:translate(-20px,20px) scale(0.95);}}\n" +
                ".grid-pattern{position:absolute;inset:0;background-image:linear-gradient(rgba(255,255,255,0.03) 1px,transparent 1px),linear-gradient(90deg,rgba(255,255,255,0.03) 1px,transparent 1px);background-size:50px 50px;pointer-events:none;}\n" +
                ".login-container{position:relative;z-index:10;width:100%;max-width:450px;margin:20px;animation:fadeInUp 0.6s ease-out;}\n" +
                "@keyframes fadeInUp{from{opacity:0;transform:translateY(30px);}to{opacity:1;transform:translateY(0);}}\n" +
                ".login-card{background:var(--card-bg);border-radius:48px;padding:48px 40px;box-shadow:0 25px 50px -12px rgba(0,0,0,0.25);backdrop-filter:blur(2px);transition:all 0.3s ease;}\n" +
                ".login-card:hover{transform:translateY(-4px);box-shadow:0 35px 60px -15px rgba(0,0,0,0.3);}\n" +
                ".logo-area{text-align:center;margin-bottom:32px;}\n" +
                ".logo-icon{width:64px;height:64px;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);border-radius:24px;display:flex;align-items:center;justify-content:center;margin:0 auto 16px;box-shadow:0 10px 20px -5px rgba(102,126,234,0.4);}\n" +
                ".logo-icon span{font-size:36px;}\n" +
                ".login-card h2{font-size:28px;font-weight:700;text-align:center;color:var(--text-primary);margin-bottom:8px;letter-spacing:-0.5px;}\n" +
                ".login-sub{text-align:center;color:var(--text-muted);font-size:14px;margin-bottom:32px;font-weight:400;}\n" +
                ".input-group{margin-bottom:20px;}\n" +
                ".input-label{display:block;font-size:13px;font-weight:500;color:var(--text-secondary);margin-bottom:8px;}\n" +
                ".input-wrapper{position:relative;}\n" +
                ".input-icon{position:absolute;left:16px;top:50%;transform:translateY(-50%);color:var(--text-muted);font-size:18px;pointer-events:none;}\n" +
                ".input-wrapper input{width:100%;height:52px;padding:0 16px 0 48px;border:1.5px solid var(--input-border);border-radius:20px;font-size:15px;color:var(--text-primary);background:var(--input-bg);transition:all 0.3s;}\n" +
                ".input-wrapper input:focus{outline:none;border-color:#667eea;box-shadow:0 0 0 4px rgba(102,126,234,0.1);}\n" +
                ".toggle-pwd{position:absolute;right:16px;top:50%;transform:translateY(-50%);cursor:pointer;color:var(--text-muted);font-size:18px;background:none;border:none;}\n" +
                ".toggle-pwd:hover{color:#667eea;}\n" +
                ".login-btn{width:100%;height:52px;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);color:white;border:none;border-radius:26px;font-size:16px;font-weight:600;cursor:pointer;transition:all 0.3s;margin-top:8px;display:flex;align-items:center;justify-content:center;gap:8px;}\n" +
                ".login-btn:hover{transform:translateY(-2px);box-shadow:0 10px 25px -5px rgba(102,126,234,0.5);}\n" +
                ".login-btn:active{transform:translateY(0);}\n" +
                ".login-btn.loading{opacity:0.7;cursor:not-allowed;}\n" +
                ".login-btn .spinner{width:20px;height:20px;border:2px solid rgba(255,255,255,0.3);border-top-color:white;border-radius:50%;animation:spin 0.8s linear infinite;display:none;}\n" +
                ".login-btn.loading .spinner{display:inline-block;}\n" +
                ".login-btn.loading .btn-text{display:none;}\n" +
                "@keyframes spin{to{transform:rotate(360deg);}}\n" +
                ".error-msg{background:var(--error-bg);border:1px solid var(--error-border);border-radius:16px;padding:12px 16px;margin-top:20px;display:none;align-items:center;gap:10px;}\n" +
                ".error-msg.show{display:flex;}\n" +
                ".error-msg .error-icon{color:var(--error-text);font-size:18px;}\n" +
                ".error-msg .error-text{color:var(--error-text);font-size:13px;flex:1;}\n" +
                ".footer{text-align:center;margin-top:24px;color:var(--text-muted);font-size:12px;}\n" +
                ".theme-toggle{position:absolute;top:20px;right:20px;background:rgba(255,255,255,0.15);border:1px solid rgba(255,255,255,0.3);color:white;padding:8px 16px;border-radius:30px;cursor:pointer;font-size:13px;z-index:20;transition:all 0.3s;}\n" +
                ".theme-toggle:hover{background:rgba(255,255,255,0.25);}\n" +
                "@media(max-width:480px){.login-card{padding:32px 24px;}.login-card h2{font-size:24px;}.input-wrapper input{height:48px;}.login-btn{height:48px;}.theme-toggle{top:16px;right:16px;padding:6px 12px;font-size:12px;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<button class=\"theme-toggle\" onclick=\"toggleTheme()\" id=\"themeBtn\">🌙 深色</button>\n" +
                "<div class=\"bg-orb orb-1\"></div>\n" +
                "<div class=\"bg-orb orb-2\"></div>\n" +
                "<div class=\"bg-orb orb-3\"></div>\n" +
                "<div class=\"grid-pattern\"></div>\n" +
                "<div class=\"login-container\">\n" +
                "<div class=\"login-card\">\n" +
                "<div class=\"logo-area\">\n" +
                "<div class=\"logo-icon\"><span>📊</span></div>\n" +
                "<h2>预付WEB 监控系统登录</h2>\n" +
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
                "// 主题切换\n" +
                "function toggleTheme() {\n" +
                "  var html = document.documentElement;\n" +
                "  var btn = document.getElementById('themeBtn');\n" +
                "  var currentTheme = html.getAttribute('data-theme');\n" +
                "  if (currentTheme === 'dark') {\n" +
                "    html.removeAttribute('data-theme');\n" +
                "    btn.innerHTML = '🌙 深色';\n" +
                "    localStorage.setItem('monitor-theme', 'light');\n" +
                "  } else {\n" +
                "    html.setAttribute('data-theme', 'dark');\n" +
                "    btn.innerHTML = '☀️ 浅色';\n" +
                "    localStorage.setItem('monitor-theme', 'dark');\n" +
                "  }\n" +
                "}\n" +
                "var savedTheme = localStorage.getItem('monitor-theme');\n" +
                "if (savedTheme === 'dark') {\n" +
                "  document.documentElement.setAttribute('data-theme', 'dark');\n" +
                "  document.getElementById('themeBtn').innerHTML = '☀️ 浅色';\n" +
                "}\n" +
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
                "<title>Texcell 预付WEB 监控首页</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='20' fill='%23667eea'/%3E%3Ctext x='50' y='68' font-size='50' text-anchor='middle' fill='white' font-family='Arial'%3E📊%3C/text%3E%3C/svg%3E\">\n" +
                "<script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:ital,wght@0,300;0,400;0,500;0,600;0,700;1,400&display=swap\" rel=\"stylesheet\">\n" +
                "<style>\n" +
                ":root {\n" +
                "  --bg-primary: #f0f4f8;\n" +
                "  --bg-card: #ffffff;\n" +
                "  --text-primary: #1e293b;\n" +
                "  --text-secondary: #64748b;\n" +
                "  --text-muted: #94a3b8;\n" +
                "  --border-color: rgba(0,0,0,0.04);\n" +
                "  --shadow: 0 4px 20px rgba(0,0,0,0.04);\n" +
                "  --navbar-bg: linear-gradient(135deg,#0f172a 0%,#1e1b4b 100%);\n" +
                "  --hover-bg: rgba(0,0,0,0.02);\n" +
                "}\n" +
                "[data-theme=\"dark\"] {\n" +
                "  --bg-primary: #0f172a;\n" +
                "  --bg-card: #1e293b;\n" +
                "  --text-primary: #f1f5f9;\n" +
                "  --text-secondary: #94a3b8;\n" +
                "  --text-muted: #64748b;\n" +
                "  --border-color: rgba(255,255,255,0.08);\n" +
                "  --shadow: 0 4px 20px rgba(0,0,0,0.3);\n" +
                "  --navbar-bg: linear-gradient(135deg,#0f172a 0%,#0f172a 100%);\n" +
                "  --hover-bg: rgba(255,255,255,0.05);\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:var(--bg-primary);font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;transition:background 0.3s,color 0.3s;}\n" +
                "/* 顶部导航栏 */\n" +
                ".navbar{background:var(--navbar-bg);box-shadow:0 4px 20px rgba(0,0,0,0.1);position:sticky;top:0;z-index:100;}\n" +
                ".nav-container{max-width:1600px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;padding:0 32px;height:70px;}\n" +
                ".logo{display:flex;align-items:center;gap:12px;}\n" +
                ".logo-icon{width:40px;height:40px;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);border-radius:12px;display:flex;align-items:center;justify-content:center;font-size:20px;}\n" +
                ".logo-text{font-size:18px;font-weight:700;color:white;letter-spacing:1px;}\n" +
                ".logo-sub{font-size:11px;color:#94a3b8;margin-left:8px;}\n" +
                ".menu{display:flex;gap:12px;}\n" +
                ".menu a{color:#e2e8f0;text-decoration:none;padding:8px 20px;border-radius:40px;transition:all 0.3s;font-weight:500;font-size:14px;}\n" +
                ".menu a:hover{background:rgba(255,255,255,0.1);color:white;}\n" +
                ".menu a.active{background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);color:white;box-shadow:0 4px 12px rgba(102,126,234,0.4);}\n" +
                ".user-info{display:flex;align-items:center;gap:20px;}\n" +
                ".user-name{color:#e2e8f0;font-size:14px;}\n" +
                ".theme-toggle{background:rgba(255,255,255,0.1);border:1px solid rgba(255,255,255,0.2);color:white;padding:8px 16px;border-radius:40px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".theme-toggle:hover{background:rgba(255,255,255,0.2);}\n" +
                ".logout-btn{background:rgba(255,255,255,0.1);border:1px solid rgba(255,255,255,0.2);color:white;padding:8px 20px;border-radius:40px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:rgba(255,255,255,0.2);transform:translateY(-1px);}\n" +
                ".page-wrap{max-width:1600px;margin:0 auto;padding:32px;}\n" +
                ".welcome-card{background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);border-radius:28px;padding:36px 40px;margin-bottom:32px;color:white;position:relative;overflow:hidden;}\n" +
                ".welcome-card::before{content:'📊';position:absolute;right:-20px;bottom:-30px;font-size:160px;opacity:0.08;pointer-events:none;}\n" +
                ".welcome-card h1{font-size:28px;font-weight:700;margin-bottom:8px;}\n" +
                ".welcome-card p{font-size:14px;opacity:0.85;margin-bottom:16px;}\n" +
                ".welcome-stats{display:flex;gap:32px;margin-top:16px;}\n" +
                ".welcome-stat{display:flex;align-items:center;gap:8px;font-size:13px;opacity:0.9;}\n" +
                ".stats-grid{display:grid;grid-template-columns:repeat(5,1fr);gap:20px;margin-bottom:32px;}\n" +
                ".stat-card{background:var(--bg-card);border-radius:24px;padding:24px 20px;box-shadow:var(--shadow);transition:all 0.3s;border:1px solid var(--border-color);}\n" +
                ".stat-card:hover{transform:translateY(-4px);box-shadow:0 20px 30px -12px rgba(102,126,234,0.2);border-color:rgba(102,126,234,0.3);}\n" +
                ".stat-icon{font-size:28px;margin-bottom:12px;}\n" +
                ".stat-number{font-size:36px;font-weight:800;color:var(--text-primary);line-height:1;}\n" +
                ".stat-label{font-size:13px;color:var(--text-secondary);margin-top:8px;font-weight:500;}\n" +
                ".stat-trend{font-size:12px;margin-top:8px;padding-top:8px;border-top:1px solid var(--border-color);color:var(--text-secondary);}\n" +
                ".section-title{font-size:18px;font-weight:700;color:var(--text-primary);margin-bottom:20px;display:flex;align-items:center;gap:8px;}\n" +
                ".section-title::before{content:'';width:4px;height:20px;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);border-radius:4px;}\n" +
                ".chart-row{display:grid;grid-template-columns:1fr 1fr;gap:24px;margin-bottom:32px;}\n" +
                ".chart-card{background:var(--bg-card);border-radius:24px;padding:24px;box-shadow:var(--shadow);border:1px solid var(--border-color);transition:all 0.3s;}\n" +
                ".chart-card:hover{box-shadow:0 12px 30px -12px rgba(0,0,0,0.15);}\n" +
                ".chart-card h3{font-size:16px;font-weight:600;color:var(--text-primary);margin-bottom:20px;display:flex;align-items:center;gap:8px;}\n" +
                ".user-list-card{background:var(--bg-card);border-radius:24px;padding:24px;box-shadow:var(--shadow);border:1px solid var(--border-color);margin-bottom:32px;}\n" +
                ".user-list-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:20px;flex-wrap:wrap;gap:12px;}\n" +
                ".user-list-header h3{font-size:16px;font-weight:600;color:var(--text-primary);display:flex;align-items:center;gap:8px;}\n" +
                ".user-badge{background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);color:white;border-radius:20px;padding:4px 12px;font-size:12px;font-weight:500;}\n" +
                ".user-items{display:flex;flex-wrap:wrap;gap:12px;}\n" +
                ".user-item{background:var(--bg-primary);border:1px solid var(--border-color);border-radius:40px;padding:8px 20px;font-size:14px;color:var(--text-primary);transition:all 0.3s;display:inline-flex;align-items:center;gap:8px;}\n" +
                ".user-item:hover{transform:translateY(-2px);border-color:#667eea;box-shadow:0 4px 12px rgba(102,126,234,0.15);}\n" +
                ".user-item .user-avatar{width:24px;height:24px;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);border-radius:50%;display:inline-flex;align-items:center;justify-content:center;font-size:12px;color:white;}\n" +
                ".empty-users{color:var(--text-muted);text-align:center;padding:32px;font-size:14px;}\n" +
                ".footer{text-align:center;padding:32px 0;color:var(--text-secondary);font-size:12px;border-top:1px solid var(--border-color);margin-top:16px;}\n" +
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
                "<div class=\"logo-text\">SMS 监控管理系统</div>\n" +
                "<div class=\"logo-sub\">v2.0</div>\n" +
                "</div>\n" +
                "<div class=\"menu\">\n" +
                "<a href=\"/monitor/index\" class=\"active\">🏠 首页</a>\n" +
                "<a href=\"/monitor/dashboard\">📈 服务监控</a>\n" +
                "<a href=\"/monitor/login-audit\">📋 登录审计</a>\n" +
                "</div>\n" +
                "<div class=\"user-info\">\n" +
                "<span class=\"user-name\">Administrator</span>\n" +
                "<button class=\"theme-toggle\" onclick=\"toggleTheme()\" id=\"themeBtn\">🌙 深色</button>\n" +
                "<button class=\"logout-btn\" onclick=\"logout()\">退出</button>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"page-wrap\">\n" +
                "<div class=\"welcome-card\">\n" +
                "<h1>欢迎回来，Administrator</h1>\n" +
                "<p>欢迎使用 SMS 监控管理系统，提供实时服务监控、登录审计、安全攻击防护等能力。</p>\n" +
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
                "// 主题切换功能\n" +
                "function toggleTheme() {\n" +
                "  var html = document.documentElement;\n" +
                "  var btn = document.getElementById('themeBtn');\n" +
                "  var currentTheme = html.getAttribute('data-theme');\n" +
                "  if (currentTheme === 'dark') {\n" +
                "    html.removeAttribute('data-theme');\n" +
                "    btn.innerHTML = '🌙 深色';\n" +
                "    localStorage.setItem('monitor-theme', 'light');\n" +
                "  } else {\n" +
                "    html.setAttribute('data-theme', 'dark');\n" +
                "    btn.innerHTML = '☀️ 浅色';\n" +
                "    localStorage.setItem('monitor-theme', 'dark');\n" +
                "  }\n" +
                "}\n" +
                "// 加载保存的主题\n" +
                "var savedTheme = localStorage.getItem('monitor-theme');\n" +
                "if (savedTheme === 'dark') {\n" +
                "  document.documentElement.setAttribute('data-theme', 'dark');\n" +
                "  document.getElementById('themeBtn').innerHTML = '☀️ 浅色';\n" +
                "}\n" +
                "function logout(){fetch('/monitor/logout').then(function(){location.href='/monitor/login';});}\n" +
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
                "fetch('/api/monitor/login-stats?secret=dn17ORUwf36Znl').then(function(res){return res.json();}).then(function(data){if(data.code===200&&data.data){var t=data.data.today||{},w=data.data.week||{},m=data.data.month||{},two=data.data.twoFactorStats||[];var google=0,email=0;for(var i=0;i<two.length;i++){var s=two[i];if(s.twoFactorType==='GOOGLE')google=s.totalCount||0;if(s.twoFactorType==='EMAIL')email=s.totalCount||0;}var successRate=t.total>0?(t.success*100/t.total).toFixed(1):0;document.getElementById('statsGrid').innerHTML='<div class=\"stat-card\"><div class=\"stat-icon\">📅</div><div class=\"stat-number\">'+(t.total||0)+'</div><div class=\"stat-label\">今日登录</div><div class=\"stat-trend\">✅成功 '+(t.success||0)+' / ❌失败 '+(t.fail||0)+'</div></div><div class=\"stat-card\"><div class=\"stat-icon\">📆</div><div class=\"stat-number\">'+(w.total||0)+'</div><div class=\"stat-label\">本周登录</div><div class=\"stat-trend\">📈 较上周 '+(w.total>m.total?'↑':'↓')+Math.abs(w.total-m.total)+'</div></div><div class=\"stat-card\"><div class=\"stat-icon\">📊</div><div class=\"stat-number\">'+(m.total||0)+'</div><div class=\"stat-label\">本月登录</div></div><div class=\"stat-card\"><div class=\"stat-icon\">🎯</div><div class=\"stat-number\">'+successRate+'%</div><div class=\"stat-label\">今日成功率</div></div><div class=\"stat-card\"><div class=\"stat-icon\">👥</div><div class=\"stat-number\">'+(data.data.todayActiveUsers||0)+'</div><div class=\"stat-label\">今日活跃账号</div></div>';document.getElementById('welcomeStats').innerHTML='<div class=\"welcome-stat\">🕐 服务运行: '+(data.runDuration||'计算中')+'</div>';// 渲染活跃账号列表\n" +
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
                "<title>Texcell 预付WEB 服务监控面板</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='20' fill='%23667eea'/%3E%3Ctext x='50' y='68' font-size='50' text-anchor='middle' fill='white' font-family='Arial'%3E📊%3C/text%3E%3C/svg%3E\">\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap\" rel=\"stylesheet\">\n" +
                "<style>\n" +
                ":root {\n" +
                "  --bg-primary: #f0f2f8;\n" +
                "  --bg-card: #ffffff;\n" +
                "  --text-primary: #1a1a2e;\n" +
                "  --text-secondary: #666;\n" +
                "  --text-muted: #8c8c8c;\n" +
                "  --border-color: rgba(0,0,0,0.04);\n" +
                "  --shadow: 0 2px 12px rgba(0,0,0,0.06);\n" +
                "  --navbar-bg: linear-gradient(135deg,#1a1a2e 0%,#16213e 100%);\n" +
                "  --log-header-bg: #fafafa;\n" +
                "  --table-hover: #fafafa;\n" +
                "  --status-header-bg: linear-gradient(135deg,#1a1a2e 0%,#16213e 100%);\n" +
                "}\n" +
                "[data-theme=\"dark\"] {\n" +
                "  --bg-primary: #0f172a;\n" +
                "  --bg-card: #1e293b;\n" +
                "  --text-primary: #f1f5f9;\n" +
                "  --text-secondary: #94a3b8;\n" +
                "  --text-muted: #64748b;\n" +
                "  --border-color: rgba(255,255,255,0.08);\n" +
                "  --shadow: 0 2px 12px rgba(0,0,0,0.3);\n" +
                "  --navbar-bg: linear-gradient(135deg,#0f172a 0%,#0f172a 100%);\n" +
                "  --log-header-bg: #334155;\n" +
                "  --table-hover: #334155;\n" +
                "  --status-header-bg: linear-gradient(135deg,#0f172a 0%,#1e1b4b 100%);\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:var(--bg-primary);font-family:'Inter',sans-serif;min-height:100vh;padding:24px;transition:background 0.3s;}\n" +
                ".page-wrap{max-width:1600px;margin:0 auto;}\n" +
                ".navbar{background:var(--navbar-bg);height:64px;display:flex;align-items:center;justify-content:space-between;padding:0 32px;color:white;border-radius:16px;margin-bottom:24px;box-shadow:0 4px 20px rgba(0,0,0,0.08);transition:background 0.3s;}\n" +
                ".logo{font-size:18px;font-weight:700;}\n" +
                ".menu{display:flex;gap:8px;}\n" +
                ".menu a{color:white;text-decoration:none;padding:8px 20px;border-radius:30px;transition:all 0.3s;font-weight:500;}\n" +
                ".menu a:hover,.menu a.active{background:rgba(255,255,255,0.2);}\n" +
                ".user-info{display:flex;align-items:center;gap:16px;}\n" +
                ".user-name{color:white;font-size:14px;}\n" +
                ".theme-toggle{background:rgba(255,255,255,0.15);border:1px solid rgba(255,255,255,0.2);color:white;padding:8px 16px;border-radius:30px;cursor:pointer;font-size:13px;transition:all 0.3s;}\n" +
                ".theme-toggle:hover{background:rgba(255,255,255,0.25);}\n" +
                ".logout-btn{background:rgba(255,255,255,0.15);border:1px solid rgba(255,255,255,0.2);color:white;padding:8px 20px;border-radius:30px;cursor:pointer;font-weight:500;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:rgba(255,255,255,0.25);transform:translateY(-1px);}\n" +
                ".status-header{background:var(--status-header-bg);border-radius:20px;padding:24px 32px;margin-bottom:28px;color:white;display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:16px;box-shadow:0 4px 20px rgba(0,0,0,0.08);transition:background 0.3s;}\n" +
                ".status-info{display:flex;gap:32px;align-items:center;flex-wrap:wrap;}\n" +
                ".status-indicator{display:flex;align-items:center;gap:10px;}\n" +
                ".status-led{width:14px;height:14px;border-radius:50%;background-color:#10b981;box-shadow:0 0 10px #10b981;animation:pulse 2s infinite;}\n" +
                "@keyframes pulse{0%,100%{opacity:1;}50%{opacity:0.5;}}\n" +
                ".status-text{font-size:15px;font-weight:500;}\n" +
                ".time-info{display:flex;gap:28px;font-size:13px;opacity:0.9;}\n" +
                ".refresh-btn{background:rgba(255,255,255,0.15);border:1px solid rgba(255,255,255,0.25);color:white;padding:8px 20px;border-radius:30px;cursor:pointer;font-size:13px;transition:all 0.3s;}\n" +
                ".refresh-btn:hover{background:rgba(255,255,255,0.25);transform:translateY(-1px);}\n" +
                ".metrics-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:24px;margin-bottom:32px;}\n" +
                ".metric-card{background:var(--bg-card);border-radius:20px;padding:24px;box-shadow:var(--shadow);transition:all 0.3s;border:1px solid var(--border-color);}\n" +
                ".metric-card:hover{transform:translateY(-2px);box-shadow:0 8px 24px rgba(0,0,0,0.1);}\n" +
                ".metric-title{font-size:14px;color:var(--text-muted);margin-bottom:10px;display:flex;align-items:center;gap:8px;font-weight:500;}\n" +
                ".metric-value{font-size:34px;font-weight:800;color:var(--text-primary);}\n" +
                ".metric-unit{font-size:14px;font-weight:400;color:var(--text-muted);margin-left:4px;}\n" +
                ".metric-sub{font-size:12px;color:var(--text-muted);margin-top:10px;padding-top:8px;border-top:1px solid var(--border-color);}\n" +
                ".module-section{margin-bottom:40px;}\n" +
                ".section-title{font-size:18px;font-weight:600;color:var(--text-primary);margin-bottom:16px;padding-left:8px;border-left:4px solid #667eea;transition:color 0.3s;}\n" +
                ".log-card{background:var(--bg-card);border-radius:20px;margin-bottom:32px;box-shadow:var(--shadow);overflow:hidden;border:1px solid var(--border-color);transition:background 0.3s;}\n" +
                ".log-card:last-child{margin-bottom:0;}\n" +
                ".log-header{padding:18px 24px;border-bottom:1px solid var(--border-color);background:var(--log-header-bg);font-weight:600;font-size:16px;display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:12px;transition:background 0.3s;}\n" +
                ".log-badge{background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);color:white;border-radius:20px;padding:4px 12px;font-size:11px;font-weight:500;}\n" +
                ".log-badge-attack{background:#e53935;color:white;border-radius:20px;padding:4px 12px;font-size:11px;font-weight:500;}\n" +
                ".table-wrapper{overflow-x:auto;}\n" +
                ".log-table{width:100%;border-collapse:collapse;}\n" +
                ".log-table th{background:var(--log-header-bg);padding:14px 16px;text-align:left;font-size:13px;font-weight:600;color:var(--text-secondary);border-bottom:1px solid var(--border-color);transition:background 0.3s;}\n" +
                ".log-table td{padding:14px 16px;font-size:13px;color:var(--text-secondary);border-bottom:1px solid var(--border-color);transition:color 0.3s;}\n" +
                ".log-table tr:hover td{background:var(--table-hover);}\n" +
                ".success{color:#10b981;font-weight:600;}.fail{color:#ef4444;font-weight:600;}\n" +
                ".gc-warn{color:#f59e0b;}.gc-error{color:#ef4444;}\n" +
                ".attack-critical{color:#ef4444;font-weight:600;}\n" +
                ".empty{text-align:center;padding:48px;color:var(--text-muted);}\n" +
                ".pagination{display:flex;justify-content:center;gap:12px;padding:16px 20px;border-top:1px solid var(--border-color);background:var(--log-header-bg);flex-wrap:wrap;}\n" +
                ".page-btn{padding:8px 16px;border:1px solid var(--border-color);background:var(--bg-card);color:var(--text-primary);border-radius:30px;cursor:pointer;font-size:13px;transition:all 0.3s;font-weight:500;}\n" +
                ".page-btn:hover:not(:disabled){border-color:#667eea;color:#667eea;transform:translateY(-1px);}\n" +
                ".page-btn:disabled{opacity:0.5;cursor:not-allowed;}\n" +
                ".page-info{font-size:13px;color:var(--text-muted);}\n" +
                ".footer{text-align:center;padding:32px;color:var(--text-muted);font-size:12px;border-top:1px solid var(--border-color);margin-top:40px;}\n" +
                "@media(max-width:768px){.metrics-grid{grid-template-columns:repeat(2,1fr);}.status-info{flex-direction:column;align-items:flex-start;}.time-info{flex-direction:column;gap:8px;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"page-wrap\">\n" +
                "<div class=\"navbar\">\n" +
                "<div class=\"logo\">📊 TexCell SMS 监控系统</div>\n" +
                "<div class=\"menu\">\n" +
                "<a href=\"/monitor/index\">🏠 首页</a>\n" +
                "<a href=\"/monitor/dashboard\" class=\"active\">📈 服务监控</a>\n" +
                "<a href=\"/monitor/login-audit\">📋 登录审计</a>\n" +
                "</div>\n" +
                "<div class=\"user-info\">\n" +
                "<span class=\"user-name\">Administrator</span>\n" +
                "<button class=\"theme-toggle\" onclick=\"toggleTheme()\" id=\"themeBtn\">🌙 深色</button>\n" +
                "<button class=\"logout-btn\" onclick=\"logout()\">退出</button>\n" +
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
                "// 主题切换功能\n" +
                "function toggleTheme() {\n" +
                "  var html = document.documentElement;\n" +
                "  var btn = document.getElementById('themeBtn');\n" +
                "  var currentTheme = html.getAttribute('data-theme');\n" +
                "  if (currentTheme === 'dark') {\n" +
                "    html.removeAttribute('data-theme');\n" +
                "    btn.innerHTML = '🌙 深色';\n" +
                "    localStorage.setItem('monitor-theme', 'light');\n" +
                "  } else {\n" +
                "    html.setAttribute('data-theme', 'dark');\n" +
                "    btn.innerHTML = '☀️ 浅色';\n" +
                "    localStorage.setItem('monitor-theme', 'dark');\n" +
                "  }\n" +
                "}\n" +
                "var savedTheme = localStorage.getItem('monitor-theme');\n" +
                "if (savedTheme === 'dark') {\n" +
                "  document.documentElement.setAttribute('data-theme', 'dark');\n" +
                "  if (document.getElementById('themeBtn')) {\n" +
                "    document.getElementById('themeBtn').innerHTML = '☀️ 浅色';\n" +
                "  }\n" +
                "}\n" +
                "function logout(){fetch('/monitor/logout').then(function(){location.href='/monitor/login';});}\n" +
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
                "var statusLed=document.getElementById('statusLed');if(statusLed)statusLed.style.backgroundColor='#10b981';\n" +
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
                "<title>Texcell 预付WEB 登录审计日志</title>\n" +
                "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='20' fill='%23667eea'/%3E%3Ctext x='50' y='68' font-size='50' text-anchor='middle' fill='white' font-family='Arial'%3E📊%3C/text%3E%3C/svg%3E\">\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap\" rel=\"stylesheet\">\n" +
                "<style>\n" +
                ":root {\n" +
                "  --bg-primary: #f0f2f5;\n" +
                "  --bg-card: #ffffff;\n" +
                "  --text-primary: #1e293b;\n" +
                "  --text-secondary: #475569;\n" +
                "  --text-muted: #94a3b8;\n" +
                "  --border-color: rgba(0,0,0,0.04);\n" +
                "  --shadow: 0 2px 12px rgba(0,0,0,0.06);\n" +
                "  --navbar-bg: linear-gradient(135deg,#667eea 0%,#764ba2 100%);\n" +
                "  --search-card-bg: #ffffff;\n" +
                "  --table-header-bg: #fafafa;\n" +
                "  --table-hover: #fafafa;\n" +
                "  --input-bg: #ffffff;\n" +
                "  --input-border: #e5e7eb;\n" +
                "  --btn-reset-bg: #f5f5f5;\n" +
                "  --btn-reset-hover: #e8e8e8;\n" +
                "  --badge-google-bg: #e8f4fd;\n" +
                "  --badge-email-bg: #e6f7e6;\n" +
                "}\n" +
                "[data-theme=\"dark\"] {\n" +
                "  --bg-primary: #0f172a;\n" +
                "  --bg-card: #1e293b;\n" +
                "  --text-primary: #f1f5f9;\n" +
                "  --text-secondary: #94a3b8;\n" +
                "  --text-muted: #64748b;\n" +
                "  --border-color: rgba(255,255,255,0.08);\n" +
                "  --shadow: 0 2px 12px rgba(0,0,0,0.3);\n" +
                "  --navbar-bg: linear-gradient(135deg,#1a1a2e 0%,#16213e 100%);\n" +
                "  --search-card-bg: #1e293b;\n" +
                "  --table-header-bg: #334155;\n" +
                "  --table-hover: #334155;\n" +
                "  --input-bg: #1e293b;\n" +
                "  --input-border: #334155;\n" +
                "  --btn-reset-bg: #334155;\n" +
                "  --btn-reset-hover: #475569;\n" +
                "  --badge-google-bg: #1e3a5f;\n" +
                "  --badge-email-bg: #1a3e2c;\n" +
                "}\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:var(--bg-primary);font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;min-height:100vh;transition:background 0.3s,color 0.3s;}\n" +
                ".page-wrap{max-width:1400px;margin:0 auto;padding:20px 24px;}\n" +
                ".navbar{background:var(--navbar-bg);height:60px;display:flex;align-items:center;justify-content:space-between;padding:0 32px;color:white;border-radius:16px;margin-bottom:24px;box-shadow:0 4px 20px rgba(0,0,0,0.08);transition:background 0.3s;}\n" +
                ".nav-links{display:flex;gap:8px;}\n" +
                ".navbar a{color:white;text-decoration:none;padding:8px 20px;border-radius:30px;font-weight:500;transition:all 0.3s;}\n" +
                ".navbar a:hover,.navbar a.active{background:rgba(255,255,255,0.2);}\n" +
                ".nav-right{display:flex;align-items:center;gap:16px;}\n" +
                ".user-name{color:white;font-size:14px;}\n" +
                ".theme-toggle{background:rgba(255,255,255,0.15);border:1px solid rgba(255,255,255,0.2);color:white;padding:8px 16px;border-radius:30px;cursor:pointer;font-size:13px;transition:all 0.3s;}\n" +
                ".theme-toggle:hover{background:rgba(255,255,255,0.25);}\n" +
                ".logout-btn{background:rgba(255,255,255,0.15);border:1px solid rgba(255,255,255,0.2);color:white;padding:8px 20px;border-radius:30px;cursor:pointer;font-weight:500;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:rgba(255,255,255,0.25);transform:translateY(-1px);}\n" +
                ".stats-grid{display:grid;grid-template-columns:repeat(5,1fr);gap:16px;margin-bottom:24px;}\n" +
                ".stat-card{background:var(--bg-card);border-radius:16px;padding:20px;box-shadow:var(--shadow);border-left:4px solid #667eea;transition:all 0.3s;border-left-color:#667eea;}\n" +
                ".stat-card:hover{transform:translateY(-2px);box-shadow:0 8px 20px rgba(0,0,0,0.08);}\n" +
                ".stat-number{font-size:32px;font-weight:700;color:#667eea;}\n" +
                ".stat-label{font-size:12px;color:var(--text-muted);margin-top:6px;}\n" +
                ".stat-sub{font-size:11px;color:var(--text-muted);margin-top:2px;}\n" +
                ".search-card{background:var(--search-card-bg);border-radius:16px;padding:20px 24px;margin-bottom:24px;box-shadow:var(--shadow);transition:background 0.3s;}\n" +
                ".search-row{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-end;margin-bottom:16px;}\n" +
                ".search-row:last-child{margin-bottom:0;}\n" +
                ".search-group{display:flex;flex-direction:column;gap:6px;}\n" +
                ".search-group label{font-size:12px;color:var(--text-secondary);font-weight:500;}\n" +
                ".search-group input,.search-group select{padding:8px 12px;border:1px solid var(--input-border);border-radius:10px;font-size:13px;min-width:140px;transition:all 0.3s;background:var(--input-bg);color:var(--text-primary);}\n" +
                ".search-group input:focus,.search-group select:focus{outline:none;border-color:#667eea;box-shadow:0 0 0 3px rgba(102,126,234,0.1);}\n" +
                ".search-group input[type=\"datetime-local\"]{min-width:180px;}\n" +
                ".search-actions{flex-direction:row;align-items:center;gap:10px;margin-left:auto;}\n" +
                ".search-btn{background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);color:white;border:none;padding:8px 20px;border-radius:30px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".search-btn:hover{transform:translateY(-1px);box-shadow:0 4px 12px rgba(102,126,234,0.4);}\n" +
                ".reset-btn{background:var(--btn-reset-bg);border:1px solid var(--input-border);padding:8px 20px;border-radius:30px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;color:var(--text-secondary);}\n" +
                ".reset-btn:hover{background:var(--btn-reset-hover);}\n" +
                ".export-btn{background:linear-gradient(135deg,#10b981 0%,#059669 100%);color:white;border:none;padding:8px 20px;border-radius:30px;cursor:pointer;font-weight:500;font-size:13px;transition:all 0.3s;}\n" +
                ".export-btn:hover{transform:translateY(-1px);box-shadow:0 4px 12px rgba(16,185,129,0.4);}\n" +
                ".log-card{background:var(--bg-card);border-radius:16px;overflow:auto;box-shadow:var(--shadow);transition:background 0.3s;}\n" +
                ".log-table{width:100%;border-collapse:collapse;}\n" +
                ".log-table th{background:var(--table-header-bg);padding:14px 16px;text-align:left;font-size:13px;font-weight:600;color:var(--text-secondary);border-bottom:1px solid var(--border-color);transition:background 0.3s;}\n" +
                ".log-table td{padding:14px 16px;font-size:13px;color:var(--text-primary);border-bottom:1px solid var(--border-color);transition:color 0.3s;}\n" +
                ".log-table tr:hover td{background:var(--table-hover);cursor:pointer;}\n" +
                ".success{color:#52c41a;font-weight:500;}.fail{color:#e53935;font-weight:500;}\n" +
                ".pagination{display:flex;justify-content:center;gap:12px;padding:24px;border-top:1px solid var(--border-color);}\n" +
                ".page-btn{padding:8px 16px;border:1px solid var(--input-border);background:var(--bg-card);color:var(--text-primary);border-radius:30px;cursor:pointer;font-size:13px;transition:all 0.3s;}\n" +
                ".page-btn:hover:not(:disabled){border-color:#667eea;color:#667eea;}\n" +
                ".page-btn:disabled{background:var(--btn-reset-bg);color:var(--text-muted);cursor:not-allowed;}\n" +
                ".page-info{font-size:13px;color:var(--text-muted);align-self:center;}\n" +
                ".badge{display:inline-block;padding:2px 8px;border-radius:20px;font-size:11px;font-weight:500;}\n" +
                ".badge-google{background:var(--badge-google-bg);color:#1890ff;}\n" +
                ".badge-email{background:var(--badge-email-bg);color:#52c41a;}\n" +
                "@media(max-width:900px){.search-actions{margin-left:0;width:100%;justify-content:flex-start;}}\n" +
                "@media(max-width:768px){.stats-grid{grid-template-columns:repeat(2,1fr);}.search-group input,.search-group select,.search-group input[type=\"datetime-local\"]{min-width:100%;width:100%;}.search-actions{flex-wrap:wrap;}.navbar{padding:0 20px;}.nav-links a{padding:6px 12px;font-size:12px;}}\n" +
                "@media(max-width:480px){.stats-grid{grid-template-columns:1fr;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"page-wrap\">\n" +
                "<div class=\"navbar\">\n" +
                "<div class=\"nav-links\">\n" +
                "<a href=\"/monitor/index\">🏠 首页</a>\n" +
                "<a href=\"/monitor/dashboard\">📊 服务监控</a>\n" +
                "<a href=\"/monitor/login-audit\" class=\"active\">📋 登录审计</a>\n" +
                "</div>\n" +
                "<div class=\"nav-right\">\n" +
                "<span class=\"user-name\">Administrator</span>\n" +
                "<button class=\"theme-toggle\" onclick=\"toggleTheme()\" id=\"themeBtn\">🌙 深色</button>\n" +
                "<button class=\"logout-btn\" onclick=\"logout()\">退出</button>\n" +
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
                "<table class=\"log-table\"><thead>在美国<th>时间</th><th>账号</th><th>IP</th><th>地理位置</th><th>结果</th><th>结果码</th><th>2FA类型</th><th>耗时(ms)</th></thead><tbody id=\"logBody\"><tr><td colspan=\"8\" style=\"text-align:center;padding:48px;\">加载中...</td></tr>\n" +
                "</tbody>\n" +
                "</table>\n" +
                "<div class=\"pagination\" id=\"pagination\"></div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<script>\n" +
                "// 主题切换功能\n" +
                "function toggleTheme() {\n" +
                "  var html = document.documentElement;\n" +
                "  var btn = document.getElementById('themeBtn');\n" +
                "  var currentTheme = html.getAttribute('data-theme');\n" +
                "  if (currentTheme === 'dark') {\n" +
                "    html.removeAttribute('data-theme');\n" +
                "    btn.innerHTML = '🌙 深色';\n" +
                "    localStorage.setItem('monitor-theme', 'light');\n" +
                "  } else {\n" +
                "    html.setAttribute('data-theme', 'dark');\n" +
                "    btn.innerHTML = '☀️ 浅色';\n" +
                "    localStorage.setItem('monitor-theme', 'dark');\n" +
                "  }\n" +
                "}\n" +
                "function logout(){fetch('/monitor/logout').then(function(){location.href='/monitor/login';});}\n" +
                "var savedTheme = localStorage.getItem('monitor-theme');\n" +
                "if (savedTheme === 'dark') {\n" +
                "  document.documentElement.setAttribute('data-theme', 'dark');\n" +
                "  var themeBtn = document.getElementById('themeBtn');\n" +
                "  if (themeBtn) themeBtn.innerHTML = '☀️ 浅色';\n" +
                "}\n" +
                "let page=1,size=20,totalPages=1;\n" +
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
                "    modal.innerHTML = '<div style=\"background:var(--bg-card);border-radius:24px;max-width:550px;width:90%;max-height:80vh;overflow:hidden;display:flex;flex-direction:column;box-shadow:0 20px 60px rgba(0,0,0,0.3);\">' +\n" +
                "        '<div style=\"padding:20px 24px;border-bottom:1px solid var(--border-color);display:flex;justify-content:space-between;align-items:center;\">' +\n" +
                "        '<h3 style=\"color:var(--text-primary);margin:0;display:flex;align-items:center;gap:8px;\"><span>📋</span> 登录详情</h3>' +\n" +
                "        '<button onclick=\"closeDetailModal()\" style=\"background:none;border:none;font-size:28px;cursor:pointer;color:var(--text-muted);line-height:1;\">&times;</button>' +\n" +
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
                "            html += '<div style=\"display:flex;border-bottom:1px solid var(--border-color);padding:10px 0;\">' +\n" +
                "                '<div style=\"width:100px;font-weight:500;color:var(--text-secondary);display:flex;align-items:center;gap:6px;\">' +\n" +
                "                '<span>' + field.icon + '</span> ' + field.key + ':</div>' +\n" +
                "                '<div style=\"flex:1;color:var(--text-primary);word-break:break-all;\">' + escapeHtml(String(value)) + '</div>' +\n" +
                "                '</div>';\n" +
                "        }\n" +
                "    }\n" +
                "    html += '</div>';\n" +
                "    container.innerHTML = html;\n" +
                "}\n" +
                "function renderDetailError(msg) {\n" +
                "    var container = document.querySelector('#detailModal > div > div:last-child');\n" +
                "    if(container){\n" +
                "        container.innerHTML = '<div style=\"text-align:center;padding:40px;color:#e53935;\">❌ ' + escapeHtml(msg) + '</div>';\n" +
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
}