package com.texcellsms.sms.monitor;

import com.texcellsms.sms.interceptor.SecurityMonitorInterceptor;
import com.texcellsms.sms.mapper.webportal.LoginAuditLogMapper;
import com.texcellsms.sms.model.entity.LoginAuditLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class LogMonitorController {

    private static final String MONITOR_SECRET = "dn17ORUwf36Znl";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // 固定管理员账号密码
    private static final String ADMIN_USERNAME = "Administrator";
    private static final String ADMIN_PASSWORD = "dn17ORUwf36Znl";

    // 内网IP白名单（支持网段匹配）
    private static final String[] INNER_IPS = {
            "127.0.0.1",
            "192.168.",
            "14.127.51.68",
            "202.77.56.146"
    };

    @Autowired(required = false)
    private LoginAuditLogMapper loginAuditLogMapper;

    @Autowired
    private LogMonitorTask logMonitorTask;

    // ====================== 登录校验 ======================
    private boolean isLogin(HttpSession session) {
        return session != null && Boolean.TRUE.equals(session.getAttribute("MONITOR_LOGIN"));
    }

    // ====================== 登录页面 ======================
    @GetMapping("/monitor/login")
    public String loginPage(HttpServletRequest request) {
        String clientIp = getRealIp(request);
        if (!isInnerIp(clientIp)) {
            return "<h1 style='color:red;text-align:center;margin-top:100px;'>❌ 仅允许内网访问<br/>您的IP：" + clientIp + "</h1>";
        }
        return generateLoginHtml();
    }

    // ====================== 登录提交 ======================
    @PostMapping("/monitor/login")
    public Map<String, Object> doLogin(
            @RequestParam String username,
            @RequestParam String password,
            HttpSession session
    ) {
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

    // ====================== 监控首页（带菜单） ======================
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

    // ====================== 原有监控接口（保持不变） ======================
    @GetMapping("/api/monitor/logs")
    public Map<String, Object> getLogMonitorData(
            @RequestParam(required = false) String secret,
            HttpServletRequest request
    ) {
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

        // 服务状态
        result.put("serviceStatus", "UP");
        result.put("serviceName", "TexCell SMS Service");
        result.put("runDuration", LogMonitorTask.getRunDuration());
        result.put("startTime", LogMonitorTask.getStartTimeStr());

        // 系统指标
        result.put("systemMetrics", LogMonitorTask.getSystemMetrics());

        // 各类监控数据
        result.put("recentErrors", LogMonitorTask.recentErrors);
        result.put("recentGcLogs", LogMonitorTask.recentGcLogs);
        result.put("recentLongSms", LogMonitorTask.recentLongSms);
        result.put("recentApiLogs", LogMonitorTask.recentApiLogs);

        // 登录日志改为从数据库查询
        if (loginAuditLogMapper != null) {
            try {
                List<LoginAuditLog> recentLoginLogs = loginAuditLogMapper.selectPage(0, 50, null, null, null, null, null);
                result.put("recentLoginLogs", recentLoginLogs);
                result.put("totalLoginCount", loginAuditLogMapper.count(null, null, null, null, null));
            } catch (Exception e) {
                result.put("recentLoginLogs", new ArrayList<>());
                result.put("totalLoginCount", 0);
            }
        } else {
            result.put("recentLoginLogs", new ArrayList<>());
            result.put("totalLoginCount", 0);
        }

        result.put("attackStatistics", SecurityMonitorInterceptor.getAttackStatistics());

        // 统计数据
        result.put("statistics", logMonitorTask.getStatistics());

        result.put("clientIp", clientIp);
        return result;
    }

    // ====================== 登录审计日志查询接口 ======================
    @GetMapping("/api/monitor/login-audit")
    public Map<String, Object> getLoginAuditLogs(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer result,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String secret,
            HttpServletRequest request
    ) {
        Map<String, Object> resultMap = new HashMap<>();
        String clientIp = getRealIp(request);

        if (!isInnerIp(clientIp)) {
            resultMap.put("code", 403);
            resultMap.put("msg", "仅允许内网访问，您的IP：" + clientIp);
            return resultMap;
        }

        if (!MONITOR_SECRET.equals(secret)) {
            resultMap.put("code", 401);
            resultMap.put("msg", "无权限访问");
            return resultMap;
        }

        if (loginAuditLogMapper == null) {
            resultMap.put("code", 500);
            resultMap.put("msg", "LoginAuditLogMapper未初始化");
            return resultMap;
        }

        try {
            int offset = (page - 1) * size;
            LocalDateTime start = null;
            LocalDateTime end = null;

            if (startTime != null && !startTime.isEmpty()) {
                start = LocalDateTime.parse(startTime, DATE_FORMATTER);
            }
            if (endTime != null && !endTime.isEmpty()) {
                end = LocalDateTime.parse(endTime, DATE_FORMATTER);
            }

            List<LoginAuditLog> logs = loginAuditLogMapper.selectPage(offset, size, username, ip, result, start, end);
            long total = loginAuditLogMapper.count(username, ip, result, start, end);

            resultMap.put("code", 200);
            resultMap.put("data", logs);
            resultMap.put("page", page);
            resultMap.put("size", size);
            resultMap.put("total", total);
            resultMap.put("totalPages", (total + size - 1) / size);
        } catch (Exception e) {
            resultMap.put("code", 500);
            resultMap.put("msg", "查询失败: " + e.getMessage());
        }

        return resultMap;
    }

    // ====================== 登录统计分析接口 ======================
    @GetMapping("/api/monitor/login-stats")
    public Map<String, Object> getLoginStats(
            @RequestParam(required = false) String secret,
            HttpServletRequest request
    ) {
        Map<String, Object> resultMap = new HashMap<>();
        String clientIp = getRealIp(request);

        if (!isInnerIp(clientIp)) {
            resultMap.put("code", 403);
            resultMap.put("msg", "仅允许内网访问，您的IP：" + clientIp);
            return resultMap;
        }

        if (!MONITOR_SECRET.equals(secret)) {
            resultMap.put("code", 401);
            resultMap.put("msg", "无权限访问");
            return resultMap;
        }

        if (loginAuditLogMapper == null) {
            resultMap.put("code", 500);
            resultMap.put("msg", "LoginAuditLogMapper未初始化");
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

            // 失败IP排行（最近7天）
            List<Map<String, Object>> topFailIps = getTopFailIps(weekStart, 10);
            stats.put("topFailIps", topFailIps);

            // 失败账号排行（最近7天）
            List<Map<String, Object>> topFailUsernames = getTopFailUsernames(weekStart, 10);
            stats.put("topFailUsernames", topFailUsernames);

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
        long total = loginAuditLogMapper.count(null, null, null, start, end);
        long success = loginAuditLogMapper.count(null, null, 1, start, end);
        long fail = loginAuditLogMapper.count(null, null, 0, start, end);

        stats.put("total", total);
        stats.put("success", success);
        stats.put("fail", fail);
        stats.put("successRate", total > 0 ? String.format("%.2f", (success * 100.0 / total)) : "0");

        return stats;
    }

    private List<Map<String, Object>> getTopFailIps(LocalDateTime startTime, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<LoginAuditLog> topIps = loginAuditLogMapper.selectTopFailIps(startTime, limit);
            for (LoginAuditLog log : topIps) {
                Map<String, Object> item = new HashMap<>();
                item.put("ip", log.getRealIp());
                item.put("failCount", log.getCacheDbRawResult());
                result.add(item);
            }
        } catch (Exception e) {
        }
        return result;
    }

    private List<Map<String, Object>> getTopFailUsernames(LocalDateTime startTime, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<LoginAuditLog> failLogs = loginAuditLogMapper.selectPage(0, 1000, null, null, 0, startTime, null);
            Map<String, Integer> userCount = new HashMap<>();
            for (LoginAuditLog log : failLogs) {
                String username = log.getUsername();
                if (username != null && !username.isEmpty()) {
                    userCount.put(username, userCount.getOrDefault(username, 0) + 1);
                }
            }

            userCount.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(limit)
                    .forEach(entry -> {
                        Map<String, Object> item = new HashMap<>();
                        item.put("username", entry.getKey());
                        item.put("failCount", entry.getValue());
                        result.add(item);
                    });
        } catch (Exception e) {
        }
        return result;
    }

    // ====================== 服务监控面板页面 ======================
    @GetMapping("/monitor/dashboard")
    public String dashboard(HttpServletRequest request) {
        String clientIp = getRealIp(request);
        if (!isInnerIp(clientIp)) {
            return "<h1 style='color:red;text-align:center;margin-top:100px;'>❌ 仅允许内网访问<br/>您的IP：" + clientIp + "</h1>";
        }
        if (!isLogin(request.getSession())) {
            return "<script>location.href='/monitor/login';</script>";
        }
        return generateMonitorHtml();
    }

    // ====================== 登录审计页面 ======================
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

    // ====================== 登录页面 ======================
    private String generateLoginHtml() {
        return "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "<title>监控系统登录</title>\n" +
                "<style>\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);min-height:100vh;display:flex;justify-content:center;align-items:center;position:relative;overflow:hidden;}\n" +
                "body::before{content:'';position:absolute;top:-50%;left:-50%;width:200%;height:200%;background:radial-gradient(circle,rgba(255,255,255,0.05) 1px,transparent 1px);background-size:40px 40px;animation:moveBg 60s linear infinite;}\n" +
                "@keyframes moveBg{0%{transform:translate(0,0);}100%{transform:translate(40px,40px);}}\n" +
                ".login-container{position:relative;z-index:1;width:100%;max-width:450px;margin:20px;}\n" +
                ".login-card{background:rgba(255,255,255,0.98);border-radius:32px;box-shadow:0 25px 50px -12px rgba(0,0,0,0.25);overflow:hidden;backdrop-filter:blur(10px);transition:transform 0.3s ease;}\n" +
                ".login-card:hover{transform:translateY(-5px);}\n" +
                ".card-header{background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);padding:32px;text-align:center;color:white;}\n" +
                ".card-header .logo{font-size:48px;margin-bottom:12px;display:inline-block;}\n" +
                ".card-header h2{font-size:24px;font-weight:600;margin-bottom:8px;}\n" +
                ".card-header p{font-size:13px;opacity:0.85;}\n" +
                ".card-body{padding:32px;}\n" +
                ".input-group{margin-bottom:24px;}\n" +
                ".input-group label{display:block;font-size:13px;font-weight:500;color:#333;margin-bottom:8px;}\n" +
                ".input-group .input-icon{position:relative;}\n" +
                ".input-group .input-icon span{position:absolute;left:14px;top:50%;transform:translateY(-50%);font-size:18px;color:#999;}\n" +
                ".input-group input{width:100%;padding:14px 14px 14px 44px;border:2px solid #e5e7eb;border-radius:14px;font-size:14px;transition:all 0.3s;background:#f9fafb;}\n" +
                ".input-group input:focus{outline:none;border-color:#667eea;background:white;box-shadow:0 0 0 4px rgba(102,126,234,0.1);}\n" +
                ".login-btn{width:100%;padding:14px;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);color:white;border:none;border-radius:14px;font-size:16px;font-weight:600;cursor:pointer;transition:all 0.3s;margin-top:8px;}\n" +
                ".login-btn:hover{transform:translateY(-2px);box-shadow:0 10px 20px -5px rgba(102,126,234,0.4);}\n" +
                ".login-btn:active{transform:translateY(0);}\n" +
                ".msg{text-align:center;margin-top:20px;font-size:13px;padding:10px;border-radius:10px;display:none;}\n" +
                ".msg.error{background:#fee2e2;color:#dc2626;display:block;}\n" +
                ".msg.success{background:#dcfce7;color:#16a34a;display:block;}\n" +
                ".card-footer{text-align:center;padding:20px 32px 32px;border-top:1px solid #f0f0f0;color:#999;font-size:12px;}\n" +
                "@keyframes shake{0%,100%{transform:translateX(0);}10%,30%,50%,70%,90%{transform:translateX(-4px);}20%,40%,60%,80%{transform:translateX(4px);}}\n" +
                ".shake{animation:shake 0.5s ease-in-out;}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"login-container\">\n" +
                "<div class=\"login-card\">\n" +
                "<div class=\"card-header\">\n" +
                "<div class=\"logo\">🔐</div>\n" +
                "<h2>监控系统登录</h2>\n" +
                "<p>TexCell SMS 服务监控平台</p>\n" +
                "</div>\n" +
                "<div class=\"card-body\">\n" +
                "<div class=\"input-group\">\n" +
                "<label>账号</label>\n" +
                "<div class=\"input-icon\">\n" +
                "<span>👤</span>\n" +
                "<input type=\"text\" id=\"username\" name=\"username\" placeholder=\"请输入账号\" autocomplete=\"off\" readonly onfocus=\"this.removeAttribute('readonly')\">\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"input-group\">\n" +
                "<label>密码</label>\n" +
                "<div class=\"input-icon\">\n" +
                "<span>🔒</span>\n" +
                "<input type=\"password\" id=\"password\" name=\"password\" placeholder=\"请输入密码\" autocomplete=\"off\" readonly onfocus=\"this.removeAttribute('readonly')\">\n" +
                "</div>\n" +
                "</div>\n" +
                "<button class=\"login-btn\" onclick=\"login()\">登 录 系 统</button>\n" +
                "<div class=\"msg\" id=\"msg\"></div>\n" +
                "</div>\n" +
                "<div class=\"card-footer\">\n" +
                "仅限内部人员访问 | 请妥善保管账号密码\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<script>\n" +
                "function showMsg(text,type){\n" +
                "let msgDiv=document.getElementById('msg');\n" +
                "msgDiv.innerText=text;\n" +
                "msgDiv.className='msg '+type;\n" +
                "setTimeout(()=>{msgDiv.className='msg';},3000);\n" +
                "}\n" +
                "function shakeCard(){document.querySelector('.login-card').classList.add('shake');setTimeout(()=>{document.querySelector('.login-card').classList.remove('shake');},500);}\n" +
                "function login(){\n" +
                "let username=document.getElementById('username').value.trim();\n" +
                "let password=document.getElementById('password').value;\n" +
                "if(!username){showMsg('请输入账号','error');document.getElementById('username').focus();shakeCard();return;}\n" +
                "if(!password){showMsg('请输入密码','error');document.getElementById('password').focus();shakeCard();return;}\n" +
                "let btn=document.querySelector('.login-btn');\n" +
                "let originalText=btn.innerText;\n" +
                "btn.innerText='登录中...';\n" +
                "btn.disabled=true;\n" +
                "fetch('/monitor/login',{\n" +
                "method:'POST',\n" +
                "headers:{'Content-Type':'application/x-www-form-urlencoded'},\n" +
                "body:'username='+encodeURIComponent(username)+'&password='+encodeURIComponent(password)\n" +
                "}).then(res=>res.json()).then(data=>{\n" +
                "if(data.code===200){\n" +
                "showMsg('登录成功，正在跳转...','success');\n" +
                "setTimeout(()=>{location.href='/monitor/index';},500);\n" +
                "}else{\n" +
                "showMsg(data.msg||'账号或密码错误','error');\n" +
                "btn.innerText=originalText;\n" +
                "btn.disabled=false;\n" +
                "shakeCard();\n" +
                "}\n" +
                "}).catch(err=>{\n" +
                "showMsg('网络错误，请重试','error');\n" +
                "btn.innerText=originalText;\n" +
                "btn.disabled=false;\n" +
                "});\n" +
                "}\n" +
                "document.getElementById('password').addEventListener('keypress',function(e){if(e.key==='Enter')login();});\n" +
                "setTimeout(function(){\n" +
                "document.getElementById('username').value='';\n" +
                "document.getElementById('password').value='';\n" +
                "},100);\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }

    // ====================== 首页 ======================
    private String generateIndexHtml() {
        return "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "<title>监控首页</title>\n" +
                "<style>\n" +
                "*{margin:0;padding:0;box-sizing:border-box;}\n" +
                "body{background:#f0f2f5;font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;}\n" +
                "/* 顶部导航栏 */\n" +
                ".top-nav{background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);box-shadow:0 4px 20px rgba(0,0,0,0.1);position:sticky;top:0;z-index:100;}\n" +
                ".nav-container{display:flex;align-items:center;justify-content:space-between;padding:0 32px;height:64px;max-width:1400px;margin:0 auto;}\n" +
                ".logo{display:flex;align-items:center;gap:12px;}\n" +
                ".logo-icon{font-size:28px;}\n" +
                ".logo-text{font-size:18px;font-weight:600;color:white;letter-spacing:1px;}\n" +
                ".logo-sub{font-size:11px;opacity:0.7;margin-left:8px;}\n" +
                ".menu{display:flex;gap:8px;}\n" +
                ".menu a{color:white;text-decoration:none;font-size:14px;padding:8px 18px;border-radius:30px;transition:all 0.3s;font-weight:500;}\n" +
                ".menu a:hover{background:rgba(255,255,255,0.2);transform:translateY(-1px);}\n" +
                ".menu a.active{background:rgba(255,255,255,0.25);box-shadow:0 2px 8px rgba(0,0,0,0.1);}\n" +
                ".user-info{display:flex;align-items:center;gap:16px;background:rgba(255,255,255,0.15);padding:6px 16px;border-radius:40px;}\n" +
                ".user-info span{font-size:13px;color:white;}\n" +
                ".user-info .logout-icon{font-size:18px;cursor:pointer;transition:0.3s;}\n" +
                ".user-info .logout-icon:hover{opacity:0.8;transform:scale(1.1);}\n" +
                "/* 主体容器 */\n" +
                ".container{padding:32px;max-width:1400px;margin:0 auto;}\n" +
                "/* 欢迎卡片 */\n" +
                ".welcome-card{background:linear-gradient(135deg,#ffffff 0%,#f8f9ff 100%);border-radius:24px;padding:32px 40px;margin-bottom:32px;box-shadow:0 4px 20px rgba(0,0,0,0.05);border:1px solid rgba(102,126,234,0.1);position:relative;overflow:hidden;}\n" +
                ".welcome-card::before{content:'📊';position:absolute;right:-20px;bottom:-20px;font-size:120px;opacity:0.05;pointer-events:none;}\n" +
                ".welcome-card h1{font-size:26px;font-weight:700;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);-webkit-background-clip:text;-webkit-text-fill-color:transparent;background-clip:text;margin-bottom:12px;}\n" +
                ".welcome-card p{color:#666;font-size:15px;line-height:1.6;max-width:80%;}\n" +
                ".welcome-card .time-info{margin-top:16px;display:flex;gap:24px;flex-wrap:wrap;}\n" +
                ".welcome-card .time-info .info-item{display:flex;align-items:center;gap:8px;font-size:13px;color:#999;}\n" +
                "/* 统计卡片 */\n" +
                ".stats-row{display:grid;grid-template-columns:repeat(4,1fr);gap:20px;margin-bottom:32px;}\n" +
                ".stat-card{background:#fff;border-radius:20px;padding:20px 24px;box-shadow:0 2px 12px rgba(0,0,0,0.04);transition:all 0.3s;border:1px solid #f0f0f0;position:relative;overflow:hidden;}\n" +
                ".stat-card:hover{transform:translateY(-4px);box-shadow:0 12px 28px rgba(102,126,234,0.15);border-color:rgba(102,126,234,0.2);}\n" +
                ".stat-icon{font-size:32px;margin-bottom:12px;}\n" +
                ".stat-value{font-size:32px;font-weight:700;color:#333;margin-bottom:4px;}\n" +
                ".stat-label{font-size:13px;color:#999;}\n" +
                ".stat-trend{position:absolute;right:20px;top:20px;font-size:12px;color:#52c41a;background:#f6ffed;padding:2px 8px;border-radius:20px;}\n" +
                "/* 模块卡片 */\n" +
                ".section-title{display:flex;align-items:center;justify-content:space-between;margin-bottom:20px;}\n" +
                ".section-title h2{font-size:18px;font-weight:600;color:#333;}\n" +
                ".section-title .more{font-size:13px;color:#667eea;text-decoration:none;}\n" +
                ".module-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:24px;}\n" +
                ".module{background:#fff;border-radius:20px;padding:28px;box-shadow:0 2px 12px rgba(0,0,0,0.04);transition:all 0.3s;border:1px solid #f0f0f0;cursor:pointer;}\n" +
                ".module:hover{transform:translateY(-4px);box-shadow:0 16px 32px rgba(0,0,0,0.08);border-color:rgba(102,126,234,0.3);}\n" +
                ".module-icon{font-size:48px;margin-bottom:16px;}\n" +
                ".module h3{font-size:18px;font-weight:600;color:#333;margin-bottom:8px;}\n" +
                ".module p{font-size:14px;color:#888;line-height:1.5;margin-bottom:20px;}\n" +
                ".module-btn{display:inline-flex;align-items:center;gap:8px;padding:8px 20px;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);color:white;border-radius:30px;font-size:13px;font-weight:500;text-decoration:none;transition:all 0.3s;}\n" +
                ".module-btn:hover{transform:translateX(4px);box-shadow:0 4px 12px rgba(102,126,234,0.4);}\n" +
                "/* 底部 */\n" +
                ".footer{text-align:center;padding:24px;margin-top:32px;border-top:1px solid #e8e8e8;color:#bbb;font-size:12px;}\n" +
                "@media(max-width:768px){.stats-row{grid-template-columns:repeat(2,1fr);}.module-grid{grid-template-columns:1fr;}.nav-container{padding:0 16px;}.menu a{padding:6px 12px;font-size:12px;}.welcome-card p{max-width:100%;}}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"top-nav\">\n" +
                "<div class=\"nav-container\">\n" +
                "<div class=\"logo\">\n" +
                "<span class=\"logo-icon\">📊</span>\n" +
                "<span class=\"logo-text\">SMS 监控管理系统</span>\n" +
                "<span class=\"logo-sub\">v1.0</span>\n" +
                "</div>\n" +
                "<div class=\"menu\">\n" +
                "<a href=\"/monitor/index\" class=\"active\">🏠 首页</a>\n" +
                "<a href=\"/monitor/dashboard\">📈 服务监控</a>\n" +
                "<a href=\"/monitor/login-audit\">🔐 登录审计</a>\n" +
                "</div>\n" +
                "<div class=\"user-info\">\n" +
                "<span>👤 Administrator</span>\n" +
                "<span class=\"logout-icon\" onclick=\"logout()\">🚪</span>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"container\">\n" +
                "<div class=\"welcome-card\">\n" +
                "<h1>欢迎回来，Administrator</h1>\n" +
                "<p>欢迎使用 SMS 监控管理系统。本系统提供实时服务监控、异常告警、登录审计、安全攻击防护等能力，仅对内网开放。</p>\n" +
                "<div class=\"time-info\">\n" +
                "<div class=\"info-item\">📅 <span id=\"currentDate\"></span></div>\n" +
                "<div class=\"info-item\">⏰ <span id=\"currentTime\"></span></div>\n" +
                "<div class=\"info-item\">🖥️ IP: <span id=\"clientIp\">加载中...</span></div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"stats-row\">\n" +
                "<div class=\"stat-card\"><div class=\"stat-icon\">📊</div><div class=\"stat-value\" id=\"totalApiCount\">-</div><div class=\"stat-label\">今日API调用</div></div>\n" +
                "<div class=\"stat-card\"><div class=\"stat-icon\">✅</div><div class=\"stat-value\" id=\"loginSuccessCount\">-</div><div class=\"stat-label\">今日登录成功</div></div>\n" +
                "<div class=\"stat-card\"><div class=\"stat-icon\">❌</div><div class=\"stat-value\" id=\"loginFailCount\">-</div><div class=\"stat-label\">今日登录失败</div></div>\n" +
                "<div class=\"stat-card\"><div class=\"stat-icon\">🛡️</div><div class=\"stat-value\" id=\"attackCount\">-</div><div class=\"stat-label\">今日攻击拦截</div></div>\n" +
                "</div>\n" +
                "<div class=\"section-title\"><h2>📌 快速入口</h2></div>\n" +
                "<div class=\"module-grid\">\n" +
                "<div class=\"module\">\n" +
                "<div class=\"module-icon\">📈</div>\n" +
                "<h3>服务监控面板</h3>\n" +
                "<p>实时查看服务状态、内存使用、线程情况、GC异常、API调用统计、安全攻击防护等全方位监控数据。</p>\n" +
                "<a href=\"/monitor/dashboard\" class=\"module-btn\">进入查看 →</a>\n" +
                "</div>\n" +
                "<div class=\"module\">\n" +
                "<div class=\"module-icon\">📋</div>\n" +
                "<h3>登录审计日志</h3>\n" +
                "<p>查询所有登录记录，支持按账号、IP、时间范围、登录结果筛选，实时掌握系统访问安全状况。</p>\n" +
                "<a href=\"/monitor/login-audit\" class=\"module-btn\">进入查看 →</a>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"footer\">TexCell SMS 监控管理系统 | 数据实时更新 | 仅限内部使用</div>\n" +
                "<script>\n" +
                "function logout(){fetch('/monitor/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'username=logout&password=logout'}).then(()=>{location.href='/monitor/login';}).catch(()=>{location.href='/monitor/login';});}\n" +
                "function updateTime(){const now=new Date();document.getElementById('currentDate').innerHTML=now.toLocaleDateString('zh-CN');document.getElementById('currentTime').innerHTML=now.toLocaleTimeString('zh-CN');}\n" +
                "function loadStats(){fetch('/api/monitor/logs?secret=dn17ORUwf36Znl').then(res=>res.json()).then(data=>{if(data.statistics){document.getElementById('totalApiCount').innerText=data.statistics.apiCallCount||0;document.getElementById('loginSuccessCount').innerText=data.statistics.loginSuccessCount||0;document.getElementById('loginFailCount').innerText=data.statistics.loginFailCount||0;}if(data.attackStatistics){const attackTypes=data.attackStatistics.attackTypes||{};let total=0;for(let key in attackTypes) total+=attackTypes[key];document.getElementById('attackCount').innerText=total;}if(data.clientIp) document.getElementById('clientIp').innerText=data.clientIp;}).catch(err=>console.error('加载失败',err));}\n" +
                "updateTime();setInterval(updateTime,1000);loadStats();setInterval(loadStats,30000);\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }

    // ====================== 监控页面 ======================
    private String generateMonitorHtml() {
        return "<!DOCTYPE html>\n"
                + "<html>\n"
                + "<head>\n"
                + "<meta charset=\"UTF-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                + "<title>TexCell SMS 服务监控面板</title>\n"
                + "<style>\n"
                + "*{margin:0;padding:0;box-sizing:border-box;}\n"
                + "body{font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', sans-serif;background:#f0f2f5;padding:20px;}\n"
                + ".top-nav{background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);height:60px;display:flex;align-items:center;gap:20px;padding:0 20px;color:white;margin-bottom:20px;border-radius:12px;}\n"
                + ".top-nav a{color:white;text-decoration:none;font-size:14px;padding:8px 12px;border-radius:6px;}\n"
                + ".top-nav a:hover{background:rgba(255,255,255,0.2);}\n"
                + ".container{max-width:1400px;margin:0 auto;}\n"
                + ".header-card{background:linear-gradient(135deg, #667eea 0%, #764ba2 100%);border-radius:16px;padding:24px 32px;margin-bottom:24px;color:white;}\n"
                + ".header-card h1{font-size:28px;margin-bottom:8px;display:flex;align-items:center;gap:12px;}\n"
                + ".header-card .sub{opacity:0.9;font-size:14px;}\n"
                + ".status-badge{display:inline-block;background:rgba(255,255,255,0.2);border-radius:20px;padding:4px 12px;font-size:12px;margin-left:12px;}\n"
                + ".metrics-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:16px;margin-bottom:24px;}\n"
                + ".metric-card{background:#fff;border-radius:12px;padding:20px;box-shadow:0 2px 8px rgba(0,0,0,0.06);}\n"
                + ".metric-title{font-size:13px;color:#8c8c8c;margin-bottom:8px;}\n"
                + ".metric-value{font-size:28px;font-weight:700;color:#262626;}\n"
                + ".metric-unit{font-size:14px;font-weight:400;color:#8c8c8c;margin-left:4px;}\n"
                + ".metric-sub{font-size:12px;color:#bfbfbf;margin-top:8px;}\n"
                + ".stats-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:16px;margin-bottom:24px;}\n"
                + ".stat-card{background:#fff;border-radius:12px;padding:16px 20px;box-shadow:0 2px 8px rgba(0,0,0,0.06);border-left:4px solid #1890ff;}\n"
                + ".stat-number{font-size:28px;font-weight:700;color:#1890ff;}\n"
                + ".stat-label{font-size:13px;color:#8c8c8c;margin-top:4px;}\n"
                + ".log-card{background:#fff;border-radius:12px;margin-bottom:24px;box-shadow:0 2px 8px rgba(0,0,0,0.06);overflow:hidden;}\n"
                + ".log-header{display:flex;justify-content:space-between;align-items:center;padding:16px 20px;border-bottom:1px solid #f0f0f0;background:#fafafa;}\n"
                + ".log-header h2{font-size:16px;font-weight:600;color:#1a1a1a;display:flex;align-items:center;gap:8px;}\n"
                + ".log-badge{background:#1890ff;color:white;border-radius:12px;padding:2px 8px;font-size:11px;font-weight:500;}\n"
                + ".log-table{width:100%;border-collapse:collapse;}\n"
                + ".log-table th{background:#f9fafb;padding:12px 16px;text-align:left;font-size:13px;font-weight:600;color:#444;border-bottom:1px solid #eee;}\n"
                + ".log-table td{padding:12px 16px;font-size:13px;color:#333;border-bottom:1px solid #f4f4f4;word-break:break-all;}\n"
                + ".log-table tr:hover td{background:#fafafa;}\n"
                + ".level-error{color:#e53935;}\n"
                + ".level-warn{color:#fa8c16;}\n"
                + ".level-attack{color:#e53935;}\n"
                + ".level-long-sms{color:#007bff;}\n"
                + ".level-success{color:#52c41a;}\n"
                + ".empty{text-align:center;padding:48px 0;color:#bfbfbf;}\n"
                + ".pagination{display:flex;justify-content:center;gap:12px;padding:16px;border-top:1px solid #f0f0f0;}\n"
                + ".page-btn{padding:6px 14px;border:1px solid #d9d9d9;background:#fff;border-radius:6px;cursor:pointer;font-size:13px;}\n"
                + ".page-btn:hover:not(:disabled){border-color:#1890ff;color:#1890ff;}\n"
                + ".page-btn:disabled{background:#f5f5f5;color:#bfbfbf;cursor:not-allowed;}\n"
                + ".page-info{font-size:13px;color:#666;align-self:center;}\n"
                + ".refresh-btn{background:rgba(255,255,255,0.2);border:1px solid rgba(255,255,255,0.3);color:white;padding:6px 14px;border-radius:6px;cursor:pointer;}\n"
                + ".refresh-btn:hover{background:rgba(255,255,255,0.3);}\n"
                + ".footer{text-align:center;padding:20px;color:#bfbfbf;font-size:12px;}\n"
                + "</style>\n"
                + "</head>\n"
                + "<body>\n"
                + "<div class=\"top-nav\">\n"
                + "<a href=\"/monitor/index\">🏠 首页</a>\n"
                + "<a href=\"/monitor/dashboard\">📊 服务监控</a>\n"
                + "<a href=\"/monitor/login-audit\">📋 登录审计</a>\n"
                + "</div>\n"
                + "<div class=\"container\">\n"
                + "<div class=\"header-card\">\n"
                + "<h1>📊 TexCell SMS 服务监控面板 <span class=\"status-badge\" id=\"statusBadge\">● 运行中</span></h1>\n"
                + "<div class=\"sub\">实时监控 | 自动刷新间隔10秒</div>\n"
                + "<div style=\"margin-top:16px;display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:12px;\">\n"
                + "<div>🖥️ 访问IP: <span id=\"clientIp\">-</span></div>\n"
                + "<div>⏱️ 已运行: <span id=\"runTime\">-</span></div>\n"
                + "<div>🚀 启动时间: <span id=\"startTime\">-</span></div>\n"
                + "<button class=\"refresh-btn\" onclick=\"load()\">🔄 刷新数据</button>\n"
                + "</div>\n"
                + "</div>\n"
                + "<div class=\"metrics-grid\" id=\"metricsGrid\"></div>\n"
                + "<div class=\"stats-grid\" id=\"statsGrid\"></div>\n"
                + "<div class=\"log-card\">\n"
                + "<div class=\"log-header\"><h2>🛡️ 安全攻击监控 <span class=\"log-badge\" style='background:#e53935'>实时防护</span></h2></div>\n"
                + "<table class=\"log-table\">\n"
                + "<thead><tr><th>时间</th><th>攻击IP</th><th>攻击类型</th><th>目标地址</th><th>详情</th></tr></thead>\n"
                + "<tbody id=\"attackLogBody\"></tbody>\n"
                + "</table>\n"
                + "<div class=\"pagination\" id=\"attackPagination\"></div>\n"
                + "</div>\n"
                + "<div class=\"log-card\">\n"
                + "<div class=\"log-header\"><h2>📨 长短信发送监控</h2></div>\n"
                + "<table class=\"log-table\"><thead><tr><th>发送记录</th></tr></thead><tbody id=\"longSmsBody\"></tbody></table>\n"
                + "<div class=\"pagination\" id=\"longSmsPagination\"></div>\n"
                + "</div>\n"
                + "<div class=\"log-card\">\n"
                + "<div class=\"log-header\"><h2>🌐 API调用监控 <span class=\"log-badge\">最近50条</span></h2></div>\n"
                + "<table class=\"log-table\"><thead><tr><th>时间</th><th>API路径</th><th>耗时(ms)</th><th>状态</th></tr></thead><tbody id=\"apiLogBody\"></tbody></table>\n"
                + "<div class=\"pagination\" id=\"apiPagination\"></div>\n"
                + "</div>\n"
                + "<div class=\"log-card\">\n"
                + "<div class=\"log-header\"><h2>🔐 登录行为监控 <span class=\"log-badge\">安全审计</span></h2></div>\n"
                + "<table class=\"log-table\"><thead><tr><th>时间</th><th>用户</th><th>IP</th><th>结果</th><th>详情</th></tr></thead><tbody id=\"loginLogBody\"></tbody></table>\n"
                + "<div class=\"pagination\" id=\"loginPagination\"></div>\n"
                + "</div>\n"
                + "<div class=\"log-card\">\n"
                + "<div class=\"log-header\"><h2>⚠️ 业务异常监控 <span class=\"log-badge\">需关注</span></h2></div>\n"
                + "<table class=\"log-table\"><thead><tr><th>时间</th><th>异常类型</th><th>关键说明</th><th>原始日志</th></tr></thead><tbody id=\"errorLogBody\"></tbody></table>\n"
                + "<div class=\"pagination\" id=\"errorPagination\"></div>\n"
                + "</div>\n"
                + "<div class=\"log-card\">\n"
                + "<div class=\"log-header\"><h2>💾 GC异常监控 <span class=\"log-badge\">内存健康</span></h2></div>\n"
                + "<table class=\"log-table\"><thead><tr><th>GC原始日志</th><th>关键说明</th></tr></thead><tbody id=\"gcLogBody\"></tbody></table>\n"
                + "<div class=\"pagination\" id=\"gcPagination\"></div>\n"
                + "</div>\n"
                + "<div class=\"footer\">TexCell SMS 监控系统 | 数据每10秒自动刷新 | 仅内网访问</div>\n"
                + "</div>\n"
                + "<script>\n"
                + "const secret = 'dn17ORUwf36Znl';\n"
                + "const PAGE_SIZE = 8;\n"
                + "let errorPage = 1, gcPage = 1, longSmsPage = 1, apiPage = 1, loginPage = 1, attackPage = 1;\n"
                + "let errorList = [], gcList = [], longSmsList = [], apiList = [], loginList = [], attackList = [];\n"
                + "function formatNumber(num){return num !== undefined ? num.toLocaleString() : '0';}\n"
                + "function getGcNote(line){if(line.includes('Full GC')) return '【严重】Full GC → 全局垃圾回收，会导致服务卡顿';if(line.includes('OutOfMemory')) return '【致命】OOM → 内存溢出，服务可能崩溃';if(line.includes('Allocation Failure')) return '【异常】内存分配失败 → 堆内存不足';if(line.includes('promotion failed')) return '【严重】对象晋升失败 → 老年代空间不足';if(line.includes('concurrent mode failure')) return '【异常】并发回收失败 → 触发Full GC';if(line.includes('Metaspace OOM')) return '【异常】元空间溢出 → 类加载过多';return 'GC异常事件';}\n"
                + "function renderAttackTable(){\n"
                + "const totalPage=Math.ceil(attackList.length/PAGE_SIZE);\n"
                + "const start=(attackPage-1)*PAGE_SIZE;\n"
                + "const pageData=attackList.slice(start,start+PAGE_SIZE);\n"
                + "let html='';\n"
                + "if(pageData.length===0) html='<tr><td colspan=\"5\" class=\"empty\">✅ 暂无安全攻击</td></tr>';\n"
                + "else pageData.forEach(line=>{\n"
                + "const parts = line.split(' | ');\n"
                + "if(parts.length>=4){\n"
                + "const time = parts[0].replace(/[\\[\\]]/g, '');\n"
                + "const ip = parts[1].replace('IP:', '');\n"
                + "const type = parts[2].replace('类型:', '');\n"
                + "const target = parts[3].replace('目标:', '');\n"
                + "const detail = parts[4] ? parts[4].replace('详情:', '') : '';\n"
                + "html+='<tr><td>'+time+'</td><td class=\"level-attack\">'+ip+'</td><td>'+type+'</td><td>'+target+'</td><td>'+detail+'</td></tr>';\n"
                + "}else{\n"
                + "html+='<tr><td colspan=5 class=\"level-attack\">'+escapeHtml(line)+'</td></tr>';\n"
                + "}\n"
                + "});\n"
                + "document.getElementById('attackLogBody').innerHTML=html;\n"
                + "renderPagination('attack',attackPage,totalPage);\n"
                + "}\n"
                + "function renderMetrics(metrics){if(!metrics) return;document.getElementById('metricsGrid').innerHTML='<div class=\"metric-card\"><div class=\"metric-title\">🧠 堆内存使用</div><div class=\"metric-value\">'+formatNumber(metrics.heapUsed)+'<span class=\"metric-unit\">MB</span></div><div class=\"metric-sub\">最大: '+formatNumber(metrics.heapMax)+' MB</div></div><div class=\"metric-card\"><div class=\"metric-title\">📦 非堆内存</div><div class=\"metric-value\">'+formatNumber(metrics.nonHeapUsed)+'<span class=\"metric-unit\">MB</span></div><div class=\"metric-sub\">已加载类: '+formatNumber(metrics.loadedClasses)+'</div></div><div class=\"metric-card\"><div class=\"metric-title\">🧵 线程状态</div><div class=\"metric-value\">'+formatNumber(metrics.threadCount)+'<span class=\"metric-unit\">个</span></div><div class=\"metric-sub\">峰值: '+formatNumber(metrics.peakThreadCount)+'</div></div><div class=\"metric-card\"><div class=\"metric-title\">⏱️ 系统负载</div><div class=\"metric-value\">'+(metrics.systemLoadAverage||0).toFixed(2)+'</div><div class=\"metric-sub\">CPU核心: '+formatNumber(metrics.availableProcessors)+'</div></div>';}\n"
                + "function renderStats(stats){if(!stats) return;document.getElementById('statsGrid').innerHTML='<div class=\"stat-card\"><div class=\"stat-number\">'+formatNumber(stats.errorCount)+'</div><div class=\"stat-label\">总异常数</div></div><div class=\"stat-card\"><div class=\"stat-number\">'+formatNumber(stats.gcCount)+'</div><div class=\"stat-label\">GC异常数</div></div><div class=\"stat-card\"><div class=\"stat-number\">'+formatNumber(stats.longSmsCount)+'</div><div class=\"stat-label\">长短信记录</div></div><div class=\"stat-card\"><div class=\"stat-number\">'+formatNumber(stats.apiCallCount)+'</div><div class=\"stat-label\">API调用次数</div></div><div class=\"stat-card\"><div class=\"stat-number\">'+formatNumber(stats.loginSuccessCount)+'/'+formatNumber(stats.loginFailCount)+'</div><div class=\"stat-label\">登录成功/失败</div></div>';}\n"
                + "function renderLongSmsTable(){const totalPage=Math.ceil(longSmsList.length/PAGE_SIZE);const start=(longSmsPage-1)*PAGE_SIZE;const pageData=longSmsList.slice(start,start+PAGE_SIZE);let html='';if(pageData.length===0)html='<tr><td class=\"empty\">✅ 暂无长短信发送记录</td></tr>';else pageData.forEach(line=>{html+='<tr><td class=\"level-long-sms\">'+escapeHtml(line)+'</td></tr>';});document.getElementById('longSmsBody').innerHTML=html;renderPagination('longSms',longSmsPage,totalPage);}\n"
                + "function renderApiTable(){const totalPage=Math.ceil(apiList.length/PAGE_SIZE);const start=(apiPage-1)*PAGE_SIZE;const pageData=apiList.slice(start,start+PAGE_SIZE);let html='';if(pageData.length===0)html='<tr><td colspan=\"4\" class=\"empty\">暂无API调用记录</td></tr>';else pageData.forEach(line=>{const m=line.match(/^\\[(.*?)\\] \\| (.*?) \\| (\\d+)ms \\| (.*)$/);if(m)html+='<tr><td>'+m[1]+'</td><td><code>'+m[2]+'</code></td><td>'+m[3]+'</td><td>'+(m[4]==='成功'?'<span style=\"color:#52c41a\">✓ '+m[4]+'</span>':'<span style=\"color:#e53935\">✗ '+m[4]+'</span>')+'</td></tr>';else html+='<tr><td colspan=\"4\">'+escapeHtml(line)+'</td></tr>';});document.getElementById('apiLogBody').innerHTML=html;renderPagination('api',apiPage,totalPage);}\n"
                + "function renderLoginTable(){const totalPage=Math.ceil(loginList.length/PAGE_SIZE);const start=(loginPage-1)*PAGE_SIZE;const pageData=loginList.slice(start,start+PAGE_SIZE);let html='';if(pageData.length===0)html='<tr><td colspan=\"5\" class=\"empty\">暂无登录记录</td></tr>';else pageData.forEach(log=>{html+='<tr><td>'+escapeHtml(log.loginTime)+'</td><td>'+escapeHtml(log.username)+'</td><td>'+escapeHtml(log.realIp)+'</td><td class=\"'+(log.loginResult===1?'level-success':'level-error')+'\">'+(log.loginResult===1?'成功':'失败')+'</td><td>'+escapeHtml(log.resultMessage||'')+'</td></tr>';});document.getElementById('loginLogBody').innerHTML=html;renderPagination('login',loginPage,totalPage);}\n"
                + "function renderErrorTable(){const totalPage=Math.ceil(errorList.length/PAGE_SIZE);const start=(errorPage-1)*PAGE_SIZE;const pageData=errorList.slice(start,start+PAGE_SIZE);let html='';if(pageData.length===0)html='<tr><td colspan=\"4\" class=\"empty\">✅ 暂无异常</td></tr>';else pageData.forEach(line=>{const m=line.match(/^\\[(.*?)\\] \\| \\[(.*?)\\] \\| (.*?) \\| (.*)$/);if(m)html+='<tr><td>'+m[1]+'</td><td class=\"level-error\">'+m[2]+'</td><td>'+escapeHtml(m[3])+'</td><td class=\"level-error\">'+escapeHtml(m[4].substring(0,100))+(m[4].length>100?'...':'')+'</td></tr>';else html+='<tr><td colspan=\"4\">'+escapeHtml(line)+'</td></tr>';});document.getElementById('errorLogBody').innerHTML=html;renderPagination('error',errorPage,totalPage);}\n"
                + "function renderGcTable(){const totalPage=Math.ceil(gcList.length/PAGE_SIZE);const start=(gcPage-1)*PAGE_SIZE;const pageData=gcList.slice(start,start+PAGE_SIZE);let html='';if(pageData.length===0)html='<tr><td colspan=\"2\" class=\"empty\">✅ GC运行正常，无异常</td></tr>';else pageData.forEach(line=>{const note=getGcNote(line);html+='<tr><td class=\"level-gc\">'+escapeHtml(line.substring(0,150))+(line.length>150?'...':'')+'</td><td class=\"level-warn\">'+note+'</td></tr>';});document.getElementById('gcLogBody').innerHTML=html;renderPagination('gc',gcPage,totalPage);}\n"
                + "function renderPagination(type,current,total){const dom=document.getElementById(type+'Pagination');let html='';if(total>1){html+='<button class=\"page-btn\" onclick=\"changePage(\\''+type+'\\',-1)\"'+(current<=1?'disabled':'')+'>上一页</button>';html+='<span class=\"page-info\">第 '+current+' / '+total+' 页</span>';html+='<button class=\"page-btn\" onclick=\"changePage(\\''+type+'\\',1)\"'+(current>=total?'disabled':'')+'>下一页</button>';}dom.innerHTML=html;}\n"
                + "function changePage(type,step){\n"
                + "if(type==='error'){errorPage+=step;renderErrorTable();}\n"
                + "else if(type==='gc'){gcPage+=step;renderGcTable();}\n"
                + "else if(type==='longSms'){longSmsPage+=step;renderLongSmsTable();}\n"
                + "else if(type==='api'){apiPage+=step;renderApiTable();}\n"
                + "else if(type==='login'){loginPage+=step;renderLoginTable();}\n"
                + "else if(type==='attack'){attackPage+=step;renderAttackTable();}\n"
                + "}\n"
                + "function escapeHtml(str){if(!str)return'';return str.replace(/[&<>]/g,function(m){if(m==='&')return'&amp;';if(m==='<')return'&lt;';if(m==='>')return'&gt;';return m;});}\n"
                + "function load(){fetch('/api/monitor/logs?secret='+secret).then(res=>res.json()).then(data=>{if(data.code===401||data.code===403){document.body.innerHTML='<div style=\"text-align:center;margin-top:100px\"><h1>🔒 无权限访问</h1><p>'+data.msg+'</p></div>';return;}document.getElementById('runTime').innerText=data.runDuration||'-';document.getElementById('startTime').innerText=data.startTime||'-';document.getElementById('clientIp').innerText=data.clientIp||'-';if(data.systemMetrics)renderMetrics(data.systemMetrics);if(data.statistics)renderStats(data.statistics);errorList=data.recentErrors||[];gcList=data.recentGcLogs||[];longSmsList=data.recentLongSms||[];apiList=data.recentApiLogs||[];loginList=data.recentLoginLogs||[];attackList=data.attackStatistics?.recentAttacks||[];renderAttackTable();renderLongSmsTable();renderApiTable();renderLoginTable();renderErrorTable();renderGcTable();}).catch(err=>console.error('加载失败',err));}\n"
                + "load();setInterval(load,10000);\n"
                + "</script>\n"
                + "</body>\n"
                + "</html>";
    }

    // ====================== 登录审计页面======================
    private String generateLoginAuditHtml() {
        return "<!DOCTYPE html>\n"
                + "<html>\n"
                + "<head>\n"
                + "<meta charset=\"UTF-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                + "<title>登录审计日志查询</title>\n"
                + "<style>\n"
                + "*{margin:0;padding:0;box-sizing:border-box;}\n"
                + "body{font-family:'Segoe UI',Roboto,'Helvetica Neue',sans-serif;background:#f0f2f5;padding:20px;}\n"
                + ".top-nav{background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);height:60px;display:flex;align-items:center;gap:20px;padding:0 20px;color:white;margin-bottom:20px;border-radius:12px;}\n"
                + ".top-nav a{color:white;text-decoration:none;font-size:14px;padding:8px 12px;border-radius:6px;}\n"
                + ".top-nav a:hover{background:rgba(255,255,255,0.2);}\n"
                + ".container{max-width:1600px;margin:0 auto;}\n"
                + ".header{background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);border-radius:16px;padding:24px 32px;margin-bottom:24px;color:white;}\n"
                + ".header h1{font-size:24px;margin-bottom:8px;}\n"
                + ".header .sub{opacity:0.9;font-size:13px;}\n"
                + ".stats-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:16px;margin-bottom:24px;}\n"
                + ".stat-card{background:#fff;border-radius:12px;padding:20px;box-shadow:0 2px 8px rgba(0,0,0,0.06);border-left:4px solid #1890ff;}\n"
                + ".stat-number{font-size:32px;font-weight:700;color:#1890ff;}\n"
                + ".stat-label{font-size:13px;color:#8c8c8c;margin-top:6px;}\n"
                + ".stat-sub{font-size:12px;color:#bfbfbf;margin-top:4px;}\n"
                + ".search-card{background:#fff;border-radius:12px;padding:20px;margin-bottom:24px;box-shadow:0 2px 8px rgba(0,0,0,0.06);}\n"
                + ".search-row{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-end;}\n"
                + ".search-group{display:flex;flex-direction:column;gap:6px;}\n"
                + ".search-group label{font-size:12px;color:#666;font-weight:500;}\n"
                + ".search-group input,.search-group select{padding:8px 12px;border:1px solid #d9d9d9;border-radius:8px;font-size:14px;width:160px;}\n"
                + ".search-group input:focus,.search-group select:focus{outline:none;border-color:#1890ff;}\n"
                + ".search-btn{background:#1890ff;color:white;border:none;padding:8px 24px;border-radius:8px;cursor:pointer;font-size:14px;}\n"
                + ".search-btn:hover{background:#40a9ff;}\n"
                + ".reset-btn{background:#f5f5f5;color:#666;border:1px solid #d9d9d9;padding:8px 24px;border-radius:8px;cursor:pointer;font-size:14px;margin-left:8px;}\n"
                + ".reset-btn:hover{background:#e8e8e8e;}\n"
                + ".log-card{background:#fff;border-radius:12px;box-shadow:0 2px 8px rgba(0,0,0,0.06);overflow:hidden;}\n"
                + ".log-table{width:100%;border-collapse:collapse;}\n"
                + ".log-table th{background:#fafafa;padding:14px 16px;text-align:left;font-size:13px;font-weight:600;color:#444;border-bottom:1px solid #f0f0f0;}\n"
                + ".log-table td{padding:12px 16px;font-size:13px;color:#333;border-bottom:1px solid #f4f4f4;word-break:break-all;}\n"
                + ".log-table tr:hover td{background:#fafafa;}\n"
                + ".success{color:#52c41a;font-weight:500;}\n"
                + ".fail{color:#e53935;font-weight:500;}\n"
                + ".pagination{display:flex;justify-content:center;gap:12px;padding:20px;border-top:1px solid #f0f0f0;}\n"
                + ".page-btn{padding:6px 14px;border:1px solid #d9d9d9;background:#fff;border-radius:6px;cursor:pointer;font-size:13px;}\n"
                + ".page-btn:hover:not(:disabled){border-color:#1890ff;color:#1890ff;}\n"
                + ".page-btn:disabled{background:#f5f5f5;color:#bfbfbf;cursor:not-allowed;}\n"
                + ".page-info{font-size:13px;color:#666;align-self:center;}\n"
                + ".loading{text-align:center;padding:40px;color:#999;}\n"
                + ".footer{text-align:center;padding:20px;color:#bfbfbf;font-size:12px;}\n"
                + "@media(max-width:768px){.stats-grid{grid-template-columns:repeat(2,1fr);}.search-group input{width:100%;}}\n"
                + "</style>\n"
                + "</head>\n"
                + "<body>\n"
                + "<div class=\"top-nav\">\n"
                + "<a href=\"/monitor/index\">🏠 首页</a>\n"
                + "<a href=\"/monitor/dashboard\">📊 服务监控</a>\n"
                + "<a href=\"/monitor/login-audit\">📋 登录审计</a>\n"
                + "</div>\n"
                + "<div class=\"container\">\n"
                + "<div class=\"header\">\n"
                + "<h1>📋 登录审计日志查询</h1>\n"
                + "<div class=\"sub\">记录所有登录请求 | 用于安全审计和问题排查</div>\n"
                + "<div style=\"margin-top:16px;display:flex;gap:20px;flex-wrap:wrap;\">\n"
                + "<div>🖥️ 访问IP: <span id=\"clientIp\">-</span></div>\n"
                + "<div>⏱️ 当前时间: <span id=\"currentTime\">-</span></div>\n"
                + "</div>\n"
                + "</div>\n"
                + "<div class=\"stats-grid\" id=\"statsGrid\">\n"
                + "<div class=\"stat-card\"><div class=\"stat-number\" id=\"todayTotal\">-</div><div class=\"stat-label\">今日登录</div><div class=\"stat-sub\">成功/失败</div></div>\n"
                + "<div class=\"stat-card\"><div class=\"stat-number\" id=\"weekTotal\">-</div><div class=\"stat-label\">本周登录</div><div class=\"stat-sub\">成功/失败</div></div>\n"
                + "<div class=\"stat-card\"><div class=\"stat-number\" id=\"monthTotal\">-</div><div class=\"stat-label\">本月登录</div><div class=\"stat-sub\">成功/失败</div></div>\n"
                + "<div class=\"stat-card\"><div class=\"stat-number\" id=\"totalCount\">-</div><div class=\"stat-label\">总登录记录</div><div class=\"stat-sub\">历史累计</div></div>\n"
                + "</div>\n"
                + "<div class=\"search-card\">\n"
                + "<div class=\"search-row\">\n"
                + "<div class=\"search-group\"><label>👤 账号</label><input type=\"text\" id=\"searchUsername\" placeholder=\"请输入账号\" style=\"width:180px\"></div>\n"
                + "<div class=\"search-group\"><label>🌐 IP地址</label><input type=\"text\" id=\"searchIp\" placeholder=\"请输入IP\" style=\"width:160px\"></div>\n"
                + "<div class=\"search-group\"><label>📊 登录结果</label><select id=\"searchResult\" style=\"width:120px\"><option value=\"\">全部</option><option value=\"1\">成功</option><option value=\"0\">失败</option></select></div>\n"
                + "<div class=\"search-group\"><label>📅 开始时间</label><input type=\"datetime-local\" id=\"startTime\" style=\"width:200px\"></div>\n"
                + "<div class=\"search-group\"><label>📅 结束时间</label><input type=\"datetime-local\" id=\"endTime\" style=\"width:200px\"></div>\n"
                + "<div class=\"search-group\"><button class=\"search-btn\" onclick=\"searchLogs()\">🔍 查询</button><button class=\"reset-btn\" onclick=\"resetSearch()\">🔄 重置</button></div>\n"
                + "</div>\n"
                + "</div>\n"
                + "<div class=\"log-card\">\n"
                + "<table class=\"log-table\">\n"
                + "<thead>"
                + "<tr><th>时间</th><th>账号</th><th>IP地址</th><th>结果</th><th>结果码</th><th>来源域名</th><th>请求URI</th></tr>\n"
                + "</thead>\n"
                + "<tbody id=\"logBody\"><tr><td colspan=\"7\" class=\"loading\">加载中...</td></tr></tbody>\n"
                + "</table>\n"
                + "<div class=\"pagination\" id=\"pagination\"></div>\n"
                + "</div>\n"
                + "<div class=\"footer\">登录审计日志 | 数据实时从数据库读取</div>\n"
                + "</div>\n"
                + "<script>\n"
                + "const secret = '" + MONITOR_SECRET + "';\n"
                + "let currentPage = 1;\n"
                + "let pageSize = 20;\n"
                + "let totalPages = 1;\n"
                + "\n"
                + "function formatDateTime(dateStr){if(!dateStr)return'-';return dateStr.replace('T',' ');}\n"
                + "\n"
                + "function loadStats(){\n"
                + "fetch('/api/monitor/login-stats?secret='+secret).then(res=>res.json()).then(data=>{\n"
                + "if(data.code===200&&data.data){\n"
                + "const today=data.data.today||{};\n"
                + "const week=data.data.week||{};\n"
                + "const month=data.data.month||{};\n"
                + "document.getElementById('todayTotal').innerHTML=today.total||0;\n"
                + "document.getElementById('todayTotal').nextElementSibling.innerHTML='成功 '+ (today.success||0)+' / 失败 '+(today.fail||0);\n"
                + "document.getElementById('weekTotal').innerHTML=week.total||0;\n"
                + "document.getElementById('weekTotal').nextElementSibling.innerHTML='成功 '+ (week.success||0)+' / 失败 '+(week.fail||0);\n"
                + "document.getElementById('monthTotal').innerHTML=month.total||0;\n"
                + "document.getElementById('monthTotal').nextElementSibling.innerHTML='成功 '+ (month.success||0)+' / 失败 '+(month.fail||0);\n"
                + "}\n"
                + "}).catch(err=>console.error('加载统计失败',err));\n"
                + "fetch('/api/monitor/logs?secret='+secret).then(res=>res.json()).then(data=>{\n"
                + "if(data.totalLoginCount!==undefined) document.getElementById('totalCount').innerHTML=data.totalLoginCount||0;\n"
                + "if(data.clientIp) document.getElementById('clientIp').innerHTML=data.clientIp;\n"
                + "}).catch(err=>console.error('加载总数失败',err));\n"
                + "}\n"
                + "\n"
                + "function searchLogs(){\n"
                + "currentPage=1;\n"
                + "loadLogs();\n"
                + "}\n"
                + "\n"
                + "function resetSearch(){\n"
                + "document.getElementById('searchUsername').value='';\n"
                + "document.getElementById('searchIp').value='';\n"
                + "document.getElementById('searchResult').value='';\n"
                + "document.getElementById('startTime').value='';\n"
                + "document.getElementById('endTime').value='';\n"
                + "currentPage=1;\n"
                + "loadLogs();\n"
                + "}\n"
                + "\n"
                + "function loadLogs(){\n"
                + "const username=document.getElementById('searchUsername').value;\n"
                + "const ip=document.getElementById('searchIp').value;\n"
                + "const result=document.getElementById('searchResult').value;\n"
                + "const startTime=document.getElementById('startTime').value;\n"
                + "const endTime=document.getElementById('endTime').value;\n"
                + "let url='/api/monitor/login-audit?secret='+secret+'&page='+currentPage+'&size='+pageSize;\n"
                + "if(username) url+='&username='+encodeURIComponent(username);\n"
                + "if(ip) url+='&ip='+encodeURIComponent(ip);\n"
                + "if(result!=='') url+='&result='+result;\n"
                + "if(startTime) url+='&startTime='+encodeURIComponent(startTime.replace('T',' '));\n"
                + "if(endTime) url+='&endTime='+encodeURIComponent(endTime.replace('T',' '));\n"
                + "fetch(url).then(res=>res.json()).then(data=>{\n"
                + "if(data.code===200){\n"
                + "renderTable(data.data||[]);\n"
                + "totalPages=data.totalPages||1;\n"
                + "renderPagination();\n"
                + "}else{\n"
                + "document.getElementById('logBody').innerHTML='<tr><td colspan=\"7\" class=\"loading\">加载失败:'+(data.msg||'错误')+'</td></tr>';\n"
                + "}\n"
                + "}).catch(err=>{\n"
                + "document.getElementById('logBody').innerHTML='<tr><td colspan=\"7\" class=\"loading\">加载失败:'+err.message+'</td></tr>';\n"
                + "});\n"
                + "}\n"
                + "\n"
                + "function renderTable(logs){\n"
                + "if(!logs||logs.length===0){\n"
                + "document.getElementById('logBody').innerHTML='<tr><td colspan=\"7\" class=\"loading\">暂无数据</td></tr>';\n"
                + "return;\n"
                + "}\n"
                + "let html='';\n"
                + "logs.forEach(log=>{\n"
                + "const loginTime=log.loginTime||'-';\n"
                + "const username=escapeHtml(log.username||'-');\n"
                + "const realIp=log.realIp||'-';\n"
                + "const resultClass=log.loginResult===1?'success':'fail';\n"
                + "const resultText=log.loginResult===1?'成功':'失败';\n"
                + "const resultCode=log.resultCode||'-';\n"
                + "const sourceDomain=log.sourceDomain||'-';\n"
                + "const requestUri=log.requestUri||'-';\n"
                + "html+='<tr><td>'+loginTime+'</td><td>'+username+'</td><td>'+realIp+'</td><td class=\"'+resultClass+'\">'+resultText+'</td><td>'+resultCode+'</td><td>'+escapeHtml(sourceDomain)+'</td><td>'+escapeHtml(requestUri)+'</td></tr>';\n"
                + "});\n"
                + "document.getElementById('logBody').innerHTML=html;\n"
                + "}\n"
                + "\n"
                + "function renderPagination(){\n"
                + "const dom=document.getElementById('pagination');\n"
                + "if(totalPages<=1){dom.innerHTML='';return;}\n"
                + "let html='<button class=\"page-btn\" onclick=\"goPage(1)\"'+(currentPage===1?'disabled':'')+'>首页</button>';\n"
                + "html+='<button class=\"page-btn\" onclick=\"goPage('+(currentPage-1)+')\"'+(currentPage===1?'disabled':'')+'>上一页</button>';\n"
                + "html+='<span class=\"page-info\">第 '+currentPage+' / '+totalPages+' 页</span>';\n"
                + "html+='<button class=\"page-btn\" onclick=\"goPage('+(currentPage+1)+')\"'+(currentPage===totalPages?'disabled':'')+'>下一页</button>';\n"
                + "html+='<button class=\"page-btn\" onclick=\"goPage('+totalPages+')\"'+(currentPage===totalPages?'disabled':'')+'>尾页</button>';\n"
                + "dom.innerHTML=html;\n"
                + "}\n"
                + "\n"
                + "function goPage(page){currentPage=page;loadLogs();}\n"
                + "function escapeHtml(str){if(!str)return'';return str.replace(/[&<>]/g,function(m){if(m==='&')return'&amp;';if(m==='<')return'&lt;';if(m==='>')return'&gt;';return m;});}\n"
                + "document.getElementById('currentTime').innerHTML=new Date().toLocaleString();\n"
                + "loadStats();\n"
                + "loadLogs();\n"
                + "setInterval(()=>{document.getElementById('currentTime').innerHTML=new Date().toLocaleString();},1000);\n"
                + "</script>\n"
                + "</body>\n"
                + "</html>";
    }

    // 获取真实IP
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

    // 判断是否内网IP
    private boolean isInnerIp(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        for (String prefix : INNER_IPS) {
            if (ip.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}