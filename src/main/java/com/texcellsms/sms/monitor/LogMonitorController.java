package com.texcellsms.sms.monitor;

import com.texcellsms.sms.interceptor.SecurityMonitorInterceptor;
import com.texcellsms.sms.mapper.webportal.LoginAuditLogMapper;
import com.texcellsms.sms.model.entity.LoginAuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
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
            "127.0.0.1", "192.168.", "10.", "172.16.",
            "14.127.51.68", "202.77.56.146"
    };

    @Autowired
    private LoginAuditLogMapper loginAuditLogMapper;

    @Autowired
    private LogMonitorTask logMonitorTask;

    private String getRealIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
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

        // 修复：查询最近50条登录日志（参数名和Mapper一致）
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
            @RequestParam(defaultValue = "20") int size,
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

            // ✅ 添加调试日志
            logger.info("接收参数 - startTime: {}, endTime: {}", startTime, endTime);

            if (startTime != null && !startTime.trim().isEmpty()) {
                try {
                    // 尝试多种格式解析
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

            resultMap.put("code", 200);
            resultMap.put("data", stats);
        } catch (Exception e) {
            resultMap.put("code", 500);
            resultMap.put("msg", "统计失败: " + e.getMessage());
        }
        return resultMap;
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
                "<html>\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "<title>监控系统登录</title>\n" +
                "<style>\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);font-family:'Inter',sans-serif;display:flex;justify-content:center;align-items:center;height:100vh;}\n" +
                ".login-box{width:420px;background:rgba(255,255,255,0.98);border-radius:32px;box-shadow:0 25px 50px rgba(0,0,0,0.2);padding:48px;backdrop-filter:blur(10px);}\n" +
                ".login-box h2{text-align:center;margin-bottom:8px;font-size:28px;color:#333;}\n" +
                ".login-sub{text-align:center;margin-bottom:32px;color:#888;font-size:14px;}\n" +
                ".input-item{margin-bottom:24px;}\n" +
                ".input-item input{width:100%;height:52px;border:2px solid #e5e7eb;border-radius:16px;padding:0 18px;font-size:15px;transition:all 0.3s;}\n" +
                ".input-item input:focus{outline:none;border-color:#667eea;box-shadow:0 0 0 4px rgba(102,126,234,0.1);}\n" +
                ".login-btn{width:100%;height:52px;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);color:white;border:none;border-radius:16px;font-size:16px;font-weight:600;cursor:pointer;transition:all 0.3s;}\n" +
                ".login-btn:hover{transform:translateY(-2px);box-shadow:0 10px 25px rgba(102,126,234,0.4);}\n" +
                ".msg{text-align:center;margin-top:20px;font-size:13px;color:#e53935;}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"login-box\">\n" +
                "<h2>🔐 监控系统登录</h2>\n" +
                "<div class=\"login-sub\">TexCell SMS 服务监控平台</div>\n" +
                "<div class=\"input-item\"><input type=\"text\" id=\"username\" placeholder=\"请输入账号\"></div>\n" +
                "<div class=\"input-item\"><input type=\"password\" id=\"password\" placeholder=\"请输入密码\"></div>\n" +
                "<button class=\"login-btn\" onclick=\"login()\">登 录</button>\n" +
                "<div class=\"msg\" id=\"msg\"></div>\n" +
                "</div>\n" +
                "<script>\n" +
                "function login(){var u=document.getElementById('username').value;var p=document.getElementById('password').value;if(!u||!p){document.getElementById('msg').innerText='请输入账号和密码';return;}fetch('/monitor/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'username='+encodeURIComponent(u)+'&password='+encodeURIComponent(p)}).then(res=>res.json()).then(data=>{if(data.code===200){location.href='/monitor/index';}else{document.getElementById('msg').innerText=data.msg;}}).catch(()=>{document.getElementById('msg').innerText='登录失败，请重试';});}\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }

    private String generateIndexHtml() {
        return "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "<title>监控首页</title>\n" +
                "<script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>\n" +
                "<style>\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:#f0f2f5;font-family:'Inter',sans-serif;}\n" +
                ".page-wrap{max-width:1400px;margin:0 auto;padding:0 24px;}\n" +
                ".navbar{background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);height:64px;display:flex;align-items:center;justify-content:space-between;padding:0 32px;color:white;box-shadow:0 4px 20px rgba(0,0,0,0.1);border-radius:0 0 20px 20px;}\n" +
                ".logo{font-size:20px;font-weight:700;}\n" +
                ".menu{display:flex;gap:8px;}\n" +
                ".menu a{color:white;text-decoration:none;padding:8px 20px;border-radius:30px;transition:all 0.3s;font-weight:500;}\n" +
                ".menu a:hover,.menu a.active{background:rgba(255,255,255,0.2);}\n" +
                ".logout-btn{background:rgba(255,255,255,0.15);border:none;color:white;padding:8px 20px;border-radius:30px;cursor:pointer;font-weight:500;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:rgba(255,255,255,0.3);}\n" +
                ".container{padding:32px 0;}\n" +
                ".welcome-card{background:linear-gradient(135deg,#fff 0%,#f8f9ff 100%);border-radius:24px;padding:32px;margin-bottom:32px;border:1px solid rgba(102,126,234,0.1);}\n" +
                ".welcome-card h1{font-size:28px;color:#333;margin-bottom:8px;}\n" +
                ".welcome-card p{color:#666;}\n" +
                ".stats-grid{display:grid;grid-template-columns:repeat(5,1fr);gap:20px;margin-bottom:32px;}\n" +
                ".stat-card{background:#fff;border-radius:20px;padding:24px;box-shadow:0 2px 12px rgba(0,0,0,0.04);transition:all 0.3s;border-left:4px solid #667eea;}\n" +
                ".stat-card:hover{transform:translateY(-4px);box-shadow:0 12px 28px rgba(102,126,234,0.15);}\n" +
                ".stat-number{font-size:36px;font-weight:700;color:#667eea;}\n" +
                ".stat-label{font-size:13px;color:#999;margin-top:8px;}\n" +
                ".stat-sub{font-size:12px;color:#bfbfbf;margin-top:4px;}\n" +
                ".chart-row{display:grid;grid-template-columns:1fr 1fr;gap:24px;margin-bottom:32px;}\n" +
                ".chart-card{background:#fff;border-radius:20px;padding:24px;box-shadow:0 2px 12px rgba(0,0,0,0.04);}\n" +
                ".chart-card h3{font-size:16px;color:#333;margin-bottom:20px;}\n" +
                ".module-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:24px;}\n" +
                ".module{background:#fff;border-radius:20px;padding:32px;box-shadow:0 2px 12px rgba(0,0,0,0.04);transition:all 0.3s;}\n" +
                ".module:hover{transform:translateY(-4px);box-shadow:0 16px 32px rgba(0,0,0,0.08);}\n" +
                ".module-icon{font-size:52px;margin-bottom:16px;}\n" +
                ".module h3{font-size:20px;margin-bottom:12px;color:#333;}\n" +
                ".module p{color:#666;margin-bottom:20px;line-height:1.5;}\n" +
                ".module-btn{display:inline-block;padding:10px 28px;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);color:white;border-radius:30px;text-decoration:none;font-size:14px;font-weight:500;transition:all 0.3s;}\n" +
                ".module-btn:hover{transform:translateX(4px);box-shadow:0 4px 12px rgba(102,126,234,0.4);}\n" +
                ".footer{text-align:center;padding:32px;color:#999;font-size:12px;border-top:1px solid #e8e8e8;margin-top:32px;}\n" +
                "@media(max-width:768px){.stats-grid{grid-template-columns:repeat(2,1fr);}.chart-row{grid-template-columns:1fr;}.module-grid{grid-template-columns:1fr;}.menu a{padding:6px 12px;font-size:13px;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"navbar\"><div class=\"logo\">📊 SMS 监控管理系统</div><div class=\"menu\"><a href=\"/monitor/index\" class=\"active\">🏠 首页</a><a href=\"/monitor/dashboard\">📈 服务监控</a><a href=\"/monitor/login-audit\">📋 登录审计</a></div><button class=\"logout-btn\" onclick=\"logout()\">退出</button></div>\n" +
                "<div class=\"page-wrap\">\n" +
                "<div class=\"container\">\n" +
                "<div class=\"welcome-card\"><h1>欢迎回来，Administrator</h1><p>欢迎使用 SMS 监控管理系统，提供实时服务监控、登录审计、安全攻击防护等能力。</p></div>\n" +
                "<div class=\"stats-grid\" id=\"statsGrid\"></div>\n" +
                "<div class=\"chart-row\"><div class=\"chart-card\"><h3>📊 今日登录趋势（按小时）</h3><canvas id=\"hourlyChart\" width=\"400\" height=\"200\"></canvas></div><div class=\"chart-card\"><h3>🥇 失败IP Top 5</h3><canvas id=\"failIpChart\" width=\"400\" height=\"200\"></canvas></div></div>\n" +
                "<div class=\"module-grid\">\n" +
                "<div class=\"module\"><div class=\"module-icon\">📈</div><h3>服务监控面板</h3><p>查看服务状态、内存、线程、GC、API调用等实时数据。</p><a href=\"/monitor/dashboard\" class=\"module-btn\">进入查看 →</a></div>\n" +
                "<div class=\"module\"><div class=\"module-icon\">📋</div><h3>登录审计日志</h3><p>查询所有登录记录，支持多维度筛选，实时掌握访问安全。</p><a href=\"/monitor/login-audit\" class=\"module-btn\">进入查看 →</a></div>\n" +
                "</div></div>\n" +
                "<div class=\"footer\">TexCell SMS 监控管理系统 | 数据实时更新 | 仅限内部使用</div>\n" +
                "</div>\n" +
                "<script>\n" +
                "function logout(){fetch('/monitor/logout').then(()=>{location.href='/monitor/login';});}\n" +
                "let hourlyChart, failIpChart;\n" +
                "fetch('/api/monitor/login-stats?secret=dn17ORUwf36Znl').then(res=>res.json()).then(data=>{if(data.code===200&&data.data){var t=data.data.today||{},w=data.data.week||{},m=data.data.month||{},two=data.data.twoFactorStats||[];var google=0,email=0;two.forEach(s=>{if(s.twoFactorType==='GOOGLE')google=s.totalCount||0;if(s.twoFactorType==='EMAIL')email=s.totalCount||0;});var successRate=t.total>0?(t.success*100/t.total).toFixed(1):0;document.getElementById('statsGrid').innerHTML='<div class=\"stat-card\"><div class=\"stat-number\">'+(t.total||0)+'</div><div class=\"stat-label\">今日登录</div><div class=\"stat-sub\">✅成功 '+(t.success||0)+' ❌失败 '+(t.fail||0)+'</div></div><div class=\"stat-card\"><div class=\"stat-number\">'+(w.total||0)+'</div><div class=\"stat-label\">本周登录</div><div class=\"stat-sub\">📈 较上周 '+(w.total>m.total?'↑':'↓')+Math.abs(w.total-m.total)+'</div></div><div class=\"stat-card\"><div class=\"stat-number\">'+(m.total||0)+'</div><div class=\"stat-label\">本月登录</div></div><div class=\"stat-card\"><div class=\"stat-number\">'+successRate+'%</div><div class=\"stat-label\">今日成功率</div></div><div class=\"stat-card\"><div class=\"stat-number\">'+(data.data.todayActiveUsers||0)+'</div><div class=\"stat-label\">今日活跃账号</div></div>';if(data.data.hourlyTrend&&data.data.hourlyTrend.length>0){var hours=[],totals=[];data.data.hourlyTrend.forEach(item=>{hours.push(item.hour+':00');totals.push(item.totalCount||0);});if(hourlyChart)hourlyChart.destroy();var ctx=document.getElementById('hourlyChart').getContext('2d');hourlyChart=new Chart(ctx,{type:'line',data:{labels:hours,datasets:[{label:'登录次数',data:totals,borderColor:'#667eea',backgroundColor:'rgba(102,126,234,0.1)',fill:true,tension:0.3}]},options:{responsive:true,maintainAspectRatio:true,plugins:{legend:{position:'top'}}}});}if(data.data.todayTopFailIps&&data.data.todayTopFailIps.length>0){var ips=[],counts=[];data.data.todayTopFailIps.forEach(item=>{ips.push(item.realIp);counts.push(item.failCount||0);});if(failIpChart)failIpChart.destroy();var ctx2=document.getElementById('failIpChart').getContext('2d');failIpChart=new Chart(ctx2,{type:'bar',data:{labels:ips,datasets:[{label:'失败次数',data:counts,backgroundColor:'#e53935',borderRadius:8}]},options:{responsive:true,maintainAspectRatio:true,plugins:{legend:{position:'top'}}}});}}}).catch(err=>console.error(err));\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }

    private String generateDashboardHtml() {
        return "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "<title>服务监控面板</title>\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap\" rel=\"stylesheet\">\n" +
                "<style>\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:#f0f2f8;font-family:'Inter',sans-serif;min-height:100vh;padding:24px;}\n" +
                ".page-wrap{max-width:1600px;margin:0 auto;}\n" +
                ".navbar{background:linear-gradient(135deg,#1a1a2e 0%,#16213e 100%);height:64px;display:flex;align-items:center;justify-content:space-between;padding:0 32px;color:white;border-radius:16px;margin-bottom:24px;box-shadow:0 4px 20px rgba(0,0,0,0.08);}\n" +
                ".logo{font-size:18px;font-weight:700;}\n" +
                ".menu{display:flex;gap:8px;}\n" +
                ".menu a{color:white;text-decoration:none;padding:8px 20px;border-radius:30px;transition:all 0.3s;font-weight:500;}\n" +
                ".menu a:hover,.menu a.active{background:rgba(255,255,255,0.2);}\n" +
                ".logout-btn{background:rgba(255,255,255,0.15);border:none;color:white;padding:8px 20px;border-radius:30px;cursor:pointer;font-weight:500;transition:all 0.3s;}\n" +
                ".logout-btn:hover{background:rgba(255,255,255,0.3);}\n" +
                ".status-header{background:linear-gradient(135deg,#1a1a2e 0%,#16213e 100%);border-radius:20px;padding:24px 32px;margin-bottom:28px;color:white;display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:16px;box-shadow:0 4px 20px rgba(0,0,0,0.08);}\n" +
                ".status-info{display:flex;gap:32px;align-items:center;flex-wrap:wrap;}\n" +
                ".status-indicator{display:flex;align-items:center;gap:10px;}\n" +
                ".status-led{width:14px;height:14px;border-radius:50%;background-color:#10b981;box-shadow:0 0 10px #10b981;animation:pulse 2s infinite;}\n" +
                "@keyframes pulse{0%,100%{opacity:1;}50%{opacity:0.5;}}\n" +
                ".status-text{font-size:15px;font-weight:500;}\n" +
                ".time-info{display:flex;gap:28px;font-size:13px;opacity:0.9;}\n" +
                ".refresh-btn{background:rgba(255,255,255,0.15);border:1px solid rgba(255,255,255,0.25);color:white;padding:8px 20px;border-radius:30px;cursor:pointer;font-size:13px;transition:all 0.3s;}\n" +
                ".refresh-btn:hover{background:rgba(255,255,255,0.25);transform:translateY(-1px);}\n" +
                ".metrics-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:24px;margin-bottom:32px;}\n" +
                ".metric-card{background:white;border-radius:20px;padding:24px;box-shadow:0 2px 12px rgba(0,0,0,0.06);transition:all 0.3s;border:1px solid rgba(0,0,0,0.04);}\n" +
                ".metric-card:hover{transform:translateY(-2px);box-shadow:0 8px 24px rgba(0,0,0,0.1);}\n" +
                ".metric-title{font-size:14px;color:#8c8c8c;margin-bottom:10px;display:flex;align-items:center;gap:8px;font-weight:500;}\n" +
                ".metric-value{font-size:34px;font-weight:800;color:#1a1a2e;}\n" +
                ".metric-unit{font-size:14px;font-weight:400;color:#8c8c8c;margin-left:4px;}\n" +
                ".metric-sub{font-size:12px;color:#bfbfbf;margin-top:10px;padding-top:8px;border-top:1px solid #f0f0f0;}\n" +
                ".module-section{margin-bottom:40px;}\n" +
                ".section-title{font-size:18px;font-weight:600;color:#333;margin-bottom:16px;padding-left:8px;border-left:4px solid #667eea;}\n" +
                ".log-card{background:white;border-radius:20px;margin-bottom:32px;box-shadow:0 2px 12px rgba(0,0,0,0.06);overflow:hidden;border:1px solid rgba(0,0,0,0.04);}\n" +
                ".log-card:last-child{margin-bottom:0;}\n" +
                ".log-header{padding:18px 24px;border-bottom:1px solid #f0f0f0;background:#fafafa;font-weight:600;font-size:16px;display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:12px;}\n" +
                ".log-badge{background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);color:white;border-radius:20px;padding:4px 12px;font-size:11px;font-weight:500;}\n" +
                ".log-badge-attack{background:#e53935;color:white;border-radius:20px;padding:4px 12px;font-size:11px;font-weight:500;}\n" +
                ".table-wrapper{overflow-x:auto;}\n" +
                ".log-table{width:100%;border-collapse:collapse;}\n" +
                ".log-table th{background:#fafbfc;padding:14px 16px;text-align:left;font-size:13px;font-weight:600;color:#555;border-bottom:1px solid #eee;}\n" +
                ".log-table td{padding:14px 16px;font-size:13px;color:#666;border-bottom:1px solid #f5f5f5;}\n" +
                ".log-table tr:hover td{background:#fafafa;}\n" +
                ".success{color:#10b981;font-weight:600;}.fail{color:#ef4444;font-weight:600;}\n" +
                ".gc-warn{color:#f59e0b;}.gc-error{color:#ef4444;}\n" +
                ".attack-critical{color:#ef4444;font-weight:600;}\n" +
                ".empty{text-align:center;padding:48px;color:#bfbfbf;}\n" +
                ".pagination{display:flex;justify-content:center;gap:12px;padding:16px 20px;border-top:1px solid #f0f0f0;background:#fafafa;flex-wrap:wrap;}\n" +
                ".page-btn{padding:8px 16px;border:1px solid #e5e7eb;background:white;border-radius:30px;cursor:pointer;font-size:13px;transition:all 0.3s;font-weight:500;}\n" +
                ".page-btn:hover:not(:disabled){border-color:#667eea;color:#667eea;transform:translateY(-1px);}\n" +
                ".page-btn:disabled{background:#f5f5f5;color:#bfbfbf;cursor:not-allowed;}\n" +
                ".page-info{font-size:13px;color:#666;}\n" +
                ".footer{text-align:center;padding:32px;color:#999;font-size:12px;border-top:1px solid #e8e8e8;margin-top:40px;}\n" +
                "@media(max-width:768px){.metrics-grid{grid-template-columns:repeat(2,1fr);}.status-info{flex-direction:column;align-items:flex-start;}.time-info{flex-direction:column;gap:8px;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"page-wrap\">\n" +
                "<div class=\"navbar\"><div class=\"logo\">📊 TexCell SMS 监控系统</div><div class=\"menu\"><a href=\"/monitor/index\">🏠 首页</a><a href=\"/monitor/dashboard\" class=\"active\">📈 服务监控</a><a href=\"/monitor/login-audit\">📋 登录审计</a></div><button class=\"logout-btn\" onclick=\"logout()\">退出</button></div>\n" +
                "<div class=\"status-header\"><div class=\"status-info\"><div class=\"status-indicator\"><div class=\"status-led\" id=\"statusLed\"></div><span class=\"status-text\" id=\"statusText\">服务运行中</span></div><div class=\"time-info\"><span>🚀 启动时间: <span id=\"startTime\">-</span></span><span>⏱️ 已运行: <span id=\"runDuration\">-</span></span><span>🖥️ 当前IP: <span id=\"currentIp\">-</span></span></div></div><button class=\"refresh-btn\" onclick=\"loadAllData()\">🔄 刷新数据</button></div>\n" +
                "<div class=\"metrics-grid\" id=\"metricsGrid\"></div>\n" +
                "<div class=\"module-section\"><div class=\"section-title\">🌐 API调用监控</div><div class=\"log-card\"><div class=\"log-header\"><span>📋 调用记录</span><span class=\"log-badge\">最近50条 | 每页10条</span></div><div class=\"table-wrapper\"><table class=\"log-table\"><thead><tr><th>时间</th><th>API路径</th><th>耗时(ms)</th><th>状态</th></tr></thead><tbody id=\"apiLogBody\"></tbody></table></div><div class=\"pagination\" id=\"apiPagination\"></div></div></div>\n" +
                "<div class=\"module-section\"><div class=\"section-title\">🛡️ 安全攻击监控</div><div class=\"log-card\"><div class=\"log-header\"><span>⚠️ 攻击日志</span><span class=\"log-badge-attack\">实时防护 | 每页10条</span></div><div class=\"table-wrapper\"><table class=\"log-table\"><thead><tr><th>时间</th><th>攻击IP</th><th>攻击类型</th><th>目标地址</th><th>详情</th></table></thead><tbody id=\"attackLogBody\"></tbody></table></div><div class=\"pagination\" id=\"attackPagination\"></div></div></div>\n" +
                "<div class=\"module-section\"><div class=\"section-title\">📨 长短信发送记录</div><div class=\"log-card\"><div class=\"log-header\"><span>✉️ 发送日志</span><span class=\"log-badge\">最近20条 | 每页10条</span></div><div class=\"table-wrapper\"><table class=\"log-table\"><thead><tr><th>时间</th><th>发送记录</th></tr></thead><tbody id=\"longSmsBody\"></tbody></table></div><div class=\"pagination\" id=\"longSmsPagination\"></div></div></div>\n" +
                "<div class=\"module-section\"><div class=\"section-title\">💾 GC异常监控</div><div class=\"log-card\"><div class=\"log-header\"><span>🗑️ 垃圾回收日志</span><span class=\"log-badge\">需关注 | 每页10条</span></div><div class=\"table-wrapper\"><table class=\"log-table\"><thead><tr><th>时间</th><th>GC原始日志</th><th>关键说明</th></tr></thead><tbody id=\"gcLogBody\"></tbody></table></div><div class=\"pagination\" id=\"gcPagination\"></div></div></div>\n" +
                "<div class=\"module-section\"><div class=\"section-title\">⚠️ 业务异常监控</div><div class=\"log-card\"><div class=\"log-header\"><span>🐛 异常日志</span><span class=\"log-badge\">最近20条 | 每页10条</span></div><div class=\"table-wrapper\"><table class=\"log-table\"><thead><tr><th>时间</th><th>异常类型</th><th>说明</th></tr></thead><tbody id=\"errorLogBody\"></tbody></table></div><div class=\"pagination\" id=\"errorPagination\"></div></div></div>\n" +
                "<div class=\"footer\">TexCell SMS 监控管理系统 | 数据实时更新 | 每30秒自动刷新</div>\n" +
                "</div>\n" +
                "<script>\n" +
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
                "function renderAttackTable(){var container=document.getElementById('attackLogBody');if(!container)return;var start=(attackPage-1)*PAGE_SIZE;var end=start+PAGE_SIZE;var pageData=attackData.slice(start,end);var html='';if(pageData.length===0){html='<tr><td colspan=\"5\" class=\"empty\">暂无安全攻击</td></tr>';}else{for(var i=0;i<pageData.length;i++){var attack=pageData[i];var parts=attack.split(' | ');if(parts.length>=4){var time=parts[0].replace(/[\\[\\]]/g,'');var ip=parts[1].replace('IP:','');var type=parts[2].replace('类型:','');var target=parts[3].replace('目标:','');var detail=parts[4]?parts[4].replace('详情:',''):'';html+='<tr><td style=\"white-space:nowrap\">'+time+'</td><td class=\"attack-critical\">'+ip+'</td><td class=\"attack-critical\">'+type+'</td><td>'+escapeHtml(target)+'</td><td>'+escapeHtml(detail)+'</td></tr>';}else{html+='<tr><td colspan=\"5\" class=\"attack-critical\">'+escapeHtml(attack)+'</td></tr>';}}}container.innerHTML=html;var totalPages=Math.ceil(attackData.length/PAGE_SIZE);var dom=document.getElementById('attackPagination');if(!dom)return;if(totalPages<=1){dom.innerHTML='';return;}dom.innerHTML='<button class=\"page-btn\" onclick=\"attackPage=1;renderAttackTable();\"'+(attackPage===1?'disabled':'')+'>首页</button><button class=\"page-btn\" onclick=\"attackPage='+(attackPage-1)+';renderAttackTable();\"'+(attackPage===1?'disabled':'')+'>上一页</button><span class=\"page-info\">第'+attackPage+'/'+totalPages+'页</span><button class=\"page-btn\" onclick=\"attackPage='+(attackPage+1)+';renderAttackTable();\"'+(attackPage===totalPages?'disabled':'')+'>下一页</button><button class=\"page-btn\" onclick=\"attackPage='+totalPages+';renderAttackTable();\"'+(attackPage===totalPages?'disabled':'')+'>尾页</button>';}\n" +
                "function renderLongSmsTable(){var container=document.getElementById('longSmsBody');if(!container)return;var start=(longSmsPage-1)*PAGE_SIZE;var end=start+PAGE_SIZE;var pageData=longSmsData.slice(start,end);var html='';if(pageData.length===0){html='<tr><td colspan=\"2\" class=\"empty\">暂无长短信记录</td></tr>';}else{for(var i=0;i<pageData.length;i++){var log=pageData[i];html+='<tr><td style=\"white-space:nowrap\">'+escapeHtml(log.substring(0,19))+'</td><td style=\"word-break:break-all\">'+escapeHtml(log)+'</td></tr>';}}container.innerHTML=html;var totalPages=Math.ceil(longSmsData.length/PAGE_SIZE);var dom=document.getElementById('longSmsPagination');if(!dom)return;if(totalPages<=1){dom.innerHTML='';return;}dom.innerHTML='<button class=\"page-btn\" onclick=\"longSmsPage=1;renderLongSmsTable();\"'+(longSmsPage===1?'disabled':'')+'>首页</button><button class=\"page-btn\" onclick=\"longSmsPage='+(longSmsPage-1)+';renderLongSmsTable();\"'+(longSmsPage===1?'disabled':'')+'>上一页</button><span class=\"page-info\">第'+longSmsPage+'/'+totalPages+'页</span><button class=\"page-btn\" onclick=\"longSmsPage='+(longSmsPage+1)+';renderLongSmsTable();\"'+(longSmsPage===totalPages?'disabled':'')+'>下一页</button><button class=\"page-btn\" onclick=\"longSmsPage='+totalPages+';renderLongSmsTable();\"'+(longSmsPage===totalPages?'disabled':'')+'>尾页</button>';}\n" +
                "function renderGcTable(){var container=document.getElementById('gcLogBody');if(!container)return;var start=(gcPage-1)*PAGE_SIZE;var end=start+PAGE_SIZE;var pageData=gcData.slice(start,end);var html='';if(pageData.length===0){html='<tr><td colspan=\"3\" class=\"empty\">✅ GC运行正常，无异常</td></tr>';}else{for(var i=0;i<pageData.length;i++){var log=pageData[i];var shortLog=log.length>100?log.substring(0,100)+'...':log;var note=getGcNote(log);html+='<tr><td style=\"white-space:nowrap\">'+log.substring(0,19)+'</td><td class=\"gc-warn\">'+escapeHtml(shortLog)+'</td><td class=\"gc-warn\">'+note+'</td></tr>';}}container.innerHTML=html;var totalPages=Math.ceil(gcData.length/PAGE_SIZE);var dom=document.getElementById('gcPagination');if(!dom)return;if(totalPages<=1){dom.innerHTML='';return;}dom.innerHTML='<button class=\"page-btn\" onclick=\"gcPage=1;renderGcTable();\"'+(gcPage===1?'disabled':'')+'>首页</button><button class=\"page-btn\" onclick=\"gcPage='+(gcPage-1)+';renderGcTable();\"'+(gcPage===1?'disabled':'')+'>上一页</button><span class=\"page-info\">第'+gcPage+'/'+totalPages+'页</span><button class=\"page-btn\" onclick=\"gcPage='+(gcPage+1)+';renderGcTable();\"'+(gcPage===totalPages?'disabled':'')+'>下一页</button><button class=\"page-btn\" onclick=\"gcPage='+totalPages+';renderGcTable();\"'+(gcPage===totalPages?'disabled':'')+'>尾页</button>';}\n" +
                "function renderErrorTable(){var container=document.getElementById('errorLogBody');if(!container)return;var start=(errorPage-1)*PAGE_SIZE;var end=start+PAGE_SIZE;var pageData=errorData.slice(start,end);var html='';if(pageData.length===0){html='<tr><td colspan=\"3\" class=\"empty\">✅ 暂无异常</td></tr>';}else{for(var i=0;i<pageData.length;i++){var e=pageData[i];var parts=e.match(/\\[(.*?)\\] \\| \\[(.*?)\\] \\| (.*?) \\| (.*)$/);if(parts){html+='<tr><td style=\"white-space:nowrap\">'+parts[1]+'</td><td class=\"fail\">'+parts[2]+'</td><td>'+escapeHtml(parts[3].substring(0,80))+'</td></tr>';}else{html+='<tr><td colspan=\"3\">'+escapeHtml(e.substring(0,100))+'</td></tr>';}}}container.innerHTML=html;var totalPages=Math.ceil(errorData.length/PAGE_SIZE);var dom=document.getElementById('errorPagination');if(!dom)return;if(totalPages<=1){dom.innerHTML='';return;}dom.innerHTML='<button class=\"page-btn\" onclick=\"errorPage=1;renderErrorTable();\"'+(errorPage===1?'disabled':'')+'>首页</button><button class=\"page-btn\" onclick=\"errorPage='+(errorPage-1)+';renderErrorTable();\"'+(errorPage===1?'disabled':'')+'>上一页</button><span class=\"page-info\">第'+errorPage+'/'+totalPages+'页</span><button class=\"page-btn\" onclick=\"errorPage='+(errorPage+1)+';renderErrorTable();\"'+(errorPage===totalPages?'disabled':'')+'>下一页</button><button class=\"page-btn\" onclick=\"errorPage='+totalPages+';renderErrorTable();\"'+(errorPage===totalPages?'disabled':'')+'>尾页</button>';}\n" +
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
                "<html>\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "<title>登录审计日志</title>\n" +
                "<style>\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:#f0f2f5;font-family:'Inter',sans-serif;}\n" +
                ".page-wrap{max-width:1400px;margin:0 auto;padding:20px 24px;}\n" +
                ".navbar{background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);height:60px;display:flex;align-items:center;gap:24px;padding:0 32px;color:white;border-radius:16px;margin-bottom:24px;}\n" +
                ".navbar a{color:white;text-decoration:none;padding:8px 20px;border-radius:30px;font-weight:500;}\n" +
                ".navbar a:hover,.navbar a.active{background:rgba(255,255,255,0.2);}\n" +
                ".stats-grid{display:grid;grid-template-columns:repeat(5,1fr);gap:16px;margin-bottom:24px;}\n" +
                ".stat-card{background:#fff;border-radius:16px;padding:20px;box-shadow:0 2px 8px rgba(0,0,0,0.04);border-left:4px solid #667eea;transition:all 0.3s;}\n" +
                ".stat-card:hover{transform:translateY(-2px);box-shadow:0 8px 20px rgba(0,0,0,0.08);}\n" +
                ".stat-number{font-size:32px;font-weight:700;color:#667eea;}\n" +
                ".stat-label{font-size:12px;color:#999;margin-top:6px;}\n" +
                ".stat-sub{font-size:11px;color:#bfbfbf;margin-top:2px;}\n" +
                ".search-card{background:#fff;border-radius:16px;padding:24px;margin-bottom:24px;box-shadow:0 2px 12px rgba(0,0,0,0.04);}\n" +
                ".search-row{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-end;}\n" +
                ".search-group{display:flex;flex-direction:column;gap:8px;}\n" +
                ".search-group label{font-size:12px;color:#666;font-weight:500;}\n" +
                ".search-group input,.search-group select{padding:10px 14px;border:1px solid #e5e7eb;border-radius:12px;font-size:13px;width:160px;transition:all 0.3s;}\n" +
                ".search-group input:focus,.search-group select:focus{outline:none;border-color:#667eea;box-shadow:0 0 0 3px rgba(102,126,234,0.1);}\n" +
                ".search-btn{background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);color:white;border:none;padding:10px 28px;border-radius:30px;cursor:pointer;font-weight:500;transition:all 0.3s;}\n" +
                ".search-btn:hover{transform:translateY(-1px);box-shadow:0 4px 12px rgba(102,126,234,0.4);}\n" +
                ".reset-btn{background:#f5f5f5;border:1px solid #e5e7eb;padding:10px 28px;border-radius:30px;cursor:pointer;margin-left:12px;font-weight:500;transition:all 0.3s;}\n" +
                ".reset-btn:hover{background:#e8e8e8;}\n" +
                ".log-card{background:#fff;border-radius:16px;overflow:auto;box-shadow:0 2px 12px rgba(0,0,0,0.04);}\n" +
                ".log-table{width:100%;border-collapse:collapse;}\n" +
                ".log-table th{background:#fafafa;padding:14px 16px;text-align:left;font-size:13px;font-weight:600;color:#444;border-bottom:1px solid #f0f0f0;}\n" +
                ".log-table td{padding:14px 16px;font-size:13px;color:#333;border-bottom:1px solid #f4f4f4;}\n" +
                ".log-table tr:hover td{background:#fafafa;}\n" +
                ".success{color:#52c41a;font-weight:500;}.fail{color:#e53935;font-weight:500;}\n" +
                ".pagination{display:flex;justify-content:center;gap:12px;padding:24px;border-top:1px solid #f0f0f0;}\n" +
                ".page-btn{padding:8px 16px;border:1px solid #e5e7eb;background:#fff;border-radius:30px;cursor:pointer;font-size:13px;transition:all 0.3s;}\n" +
                ".page-btn:hover:not(:disabled){border-color:#667eea;color:#667eea;}\n" +
                ".page-btn:disabled{background:#f5f5f5;color:#bfbfbf;cursor:not-allowed;}\n" +
                ".page-info{font-size:13px;color:#666;align-self:center;}\n" +
                ".badge{display:inline-block;padding:2px 8px;border-radius:20px;font-size:11px;font-weight:500;}\n" +
                ".badge-google{background:#e8f4fd;color:#1890ff;}\n" +
                ".badge-email{background:#e6f7e6;color:#52c41a;}\n" +
                "@media(max-width:768px){.stats-grid{grid-template-columns:repeat(2,1fr);}.search-group input{width:100%;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"page-wrap\">\n" +
                "<div class=\"navbar\"><a href=\"/monitor/index\">🏠 首页</a><a href=\"/monitor/dashboard\">📊 服务监控</a><a href=\"/monitor/login-audit\" class=\"active\">📋 登录审计</a></div>\n" +
                "<div class=\"stats-grid\" id=\"statsGrid\"></div>\n" +
                "<div class=\"search-card\"><div class=\"search-row\">\n" +
                "<div class=\"search-group\"><label>👤 账号</label><input type=\"text\" id=\"searchUsername\" placeholder=\"模糊查询\"></div>\n" +
                "<div class=\"search-group\"><label>🌐 IP地址</label><input type=\"text\" id=\"searchIp\" placeholder=\"模糊查询\"></div>\n" +
                "<div class=\"search-group\"><label>📊 结果</label><select id=\"searchResult\"><option value=\"\">全部</option><option value=\"1\">成功</option><option value=\"0\">失败</option></select></div>\n" +
                "<div class=\"search-group\"><label>🔐 2FA类型</label><select id=\"searchTwoFactor\"><option value=\"\">全部</option><option value=\"GOOGLE\">Google验证器</option><option value=\"EMAIL\">邮箱验证</option></select></div>\n" +
                "<div class=\"search-group\"><label>📅 开始时间</label><input type=\"datetime-local\" id=\"startTime\"></div>\n" +
                "<div class=\"search-group\"><label>📅 结束时间</label><input type=\"datetime-local\" id=\"endTime\"></div>\n" +
                "<div class=\"search-group\"><button class=\"search-btn\" onclick=\"searchLogs()\">🔍 查询</button><button class=\"reset-btn\" onclick=\"resetSearch()\">重置</button></div>\n" +
                "</div></div>\n" +
                "<div class=\"log-card\">\n" +
                "<table class=\"log-table\"><thead><th>时间</th><th>账号</th><th>IP</th><th>地理位置</th><th>结果</th><th>结果码</th><th>2FA类型</th><th>耗时(ms)</th></thead><tbody id=\"logBody\"><tr><td colspan=\"8\" style=\"text-align:center;padding:48px;\">加载中...</td></tr></tbody></table>\n" +
                "<div class=\"pagination\" id=\"pagination\"></div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<script>\n" +
                "let page=1,size=20,totalPages=1;\n" +
                "function formatTwoFactor(tf){if(tf==='GOOGLE')return'<span class=\"badge badge-google\">🔐 Google</span>';if(tf==='EMAIL')return'<span class=\"badge badge-email\">📧 邮箱</span>';return'-';}\n" +
                "function loadStats(){fetch('/api/monitor/login-stats?secret=dn17ORUwf36Znl').then(res=>res.json()).then(data=>{if(data.code===200&&data.data){var t=data.data.today||{},w=data.data.week||{},m=data.data.month||{},two=data.data.twoFactorStats||[];var google=0,email=0;two.forEach(s=>{if(s.twoFactorType==='GOOGLE')google=s.totalCount||0;if(s.twoFactorType==='EMAIL')email=s.totalCount||0;});var successRate=t.total>0?((t.success*100)/t.total).toFixed(1):0;document.getElementById('statsGrid').innerHTML='<div class=\"stat-card\"><div class=\"stat-number\">'+t.total+'</div><div class=\"stat-label\">今日登录</div><div class=\"stat-sub\">✅成功 '+t.success+' / ❌失败 '+t.fail+'</div></div><div class=\"stat-card\"><div class=\"stat-number\">'+w.total+'</div><div class=\"stat-label\">本周登录</div></div><div class=\"stat-card\"><div class=\"stat-number\">'+m.total+'</div><div class=\"stat-label\">本月登录</div></div><div class=\"stat-card\"><div class=\"stat-number\">'+successRate+'%</div><div class=\"stat-label\">今日成功率</div></div><div class=\"stat-card\"><div class=\"stat-number\">'+google+'/'+email+'</div><div class=\"stat-label\">Google/邮箱2FA</div></div>';}}).catch(err=>console.error(err));}\n" +
                "function searchLogs(){page=1;loadLogs();}\n" +
                "function resetSearch(){document.getElementById('searchUsername').value='';document.getElementById('searchIp').value='';document.getElementById('searchResult').value='';document.getElementById('searchTwoFactor').value='';document.getElementById('startTime').value='';document.getElementById('endTime').value='';page=1;loadLogs();}\n" +
                "function loadLogs(){var u=document.getElementById('searchUsername').value;var ip=document.getElementById('searchIp').value;var r=document.getElementById('searchResult').value;var tf=document.getElementById('searchTwoFactor').value;var st=document.getElementById('startTime').value;var et=document.getElementById('endTime').value;var url='/api/monitor/login-audit?secret=dn17ORUwf36Znl&page='+page+'&size='+size;if(u)url+='&username='+encodeURIComponent(u);if(ip)url+='&ip='+encodeURIComponent(ip);if(r)url+='&result='+r;if(tf)url+='&twoFactorType='+tf;if(st&&st.trim()!==''){var startTimeStr=st.replace('T',' ');if(startTimeStr.length===16)startTimeStr+=':00';url+='&startTime='+encodeURIComponent(startTimeStr);}if(et&&et.trim()!==''){var endTimeStr=et.replace('T',' ');if(endTimeStr.length===16)endTimeStr+=':00';url+='&endTime='+encodeURIComponent(endTimeStr);}fetch(url).then(res=>res.json()).then(data=>{if(data.code===200){renderTable(data.data||[]);totalPages=data.totalPages||1;renderPagination();}else{document.getElementById('logBody').innerHTML='<tr><td colspan=\"8\" style=\"text-align:center;padding:48px;\">加载失败:'+(data.msg||'错误')+'</td></tr>';}}).catch(()=>{document.getElementById('logBody').innerHTML='<tr><td colspan=\"8\" style=\"text-align:center;padding:48px;\">加载失败</td></tr>';});}\n" +
                "function renderTable(logs){if(!logs||logs.length===0){document.getElementById('logBody').innerHTML='<tr><td colspan=\"8\" style=\"text-align:center;padding:48px;\">暂无数据</td></tr>';return;}var html='';logs.forEach(l=>{var loc=l.ipLocation||(l.ipCountry?l.ipCountry+(l.ipCity?l.ipCity:''):'-');html+='<tr><td style=\"white-space:nowrap\">'+l.loginTime+'</td><td>'+escapeHtml(l.username)+'</td><td>'+l.realIp+'</td><td>'+escapeHtml(loc)+'</td><td class=\"'+(l.loginResult===1?'success':'fail')+'\">'+(l.loginResult===1?'成功':'失败')+'</td><td>'+l.resultCode+'</td><td>'+formatTwoFactor(l.twoFactorType)+'</td><td>'+(l.loginDuration||'-')+'</td></tr>';});document.getElementById('logBody').innerHTML=html;}\n" +
                "function renderPagination(){var dom=document.getElementById('pagination');if(totalPages<=1){dom.innerHTML='';return;}var html='<button class=\"page-btn\" onclick=\"goPage(1)\"'+(page===1?'disabled':'')+'>首页</button><button class=\"page-btn\" onclick=\"goPage('+(page-1)+')\"'+(page===1?'disabled':'')+'>上一页</button><span class=\"page-info\">第 '+page+' / '+totalPages+' 页</span><button class=\"page-btn\" onclick=\"goPage('+(page+1)+')\"'+(page===totalPages?'disabled':'')+'>下一页</button><button class=\"page-btn\" onclick=\"goPage('+totalPages+')\"'+(page===totalPages?'disabled':'')+'>尾页</button>';dom.innerHTML=html;}\n" +
                "function goPage(p){page=p;loadLogs();}\n" +
                "function escapeHtml(s){if(!s)return'';return s.replace(/[&<>]/g,function(m){if(m==='&')return'&amp;';if(m==='<')return'&lt;';if(m==='>')return'&gt;';return m;});}\n" +
                "loadStats();loadLogs();\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }
}