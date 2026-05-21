package com.texcellsms.sms.monitor;

import com.texcellsms.sms.interceptor.SecurityMonitorInterceptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

@RestController
public class LogMonitorController {

    private static final String MONITOR_SECRET = "dn17ORUwf36Znl";

    // 内网IP白名单（支持网段匹配）
    private static final String[] INNER_IPS = {
            "127.0.0.1"
    };

    // 获取真实客户端IP
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
        result.put("recentLoginLogs", LogMonitorTask.recentLoginLogs);
        result.put("attackStatistics", SecurityMonitorInterceptor.getAttackStatistics());

        // 统计数据
        result.put("statistics", LogMonitorTask.getStatistics());

        result.put("clientIp", clientIp);
        return result;
    }

    @GetMapping("/monitor")
    public String getMonitorPage(
            @RequestParam(required = false) String secret,
            HttpServletRequest request
    ) {
        String clientIp = getRealIp(request);
        if (!isInnerIp(clientIp)) {
            return "<h1 style='color:red;text-align:center;margin-top:100px;'>❌ 仅允许内网访问<br/>您的IP：" + clientIp + "</h1>";
        }

        if (!MONITOR_SECRET.equals(secret)) {
            return "<h1 style='color:red;text-align:center;margin-top:100px;'>🔒 无权限访问</h1><p style='text-align:center'>请联系管理员获取访问密钥</p>";
        }

        return generateMonitorHtml();
    }

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
                + ".container{max-width:1600px;margin:0 auto;}\n"
                + "/* 头部卡片 */\n"
                + ".header-card{background:linear-gradient(135deg, #667eea 0%, #764ba2 100%);border-radius:16px;padding:24px 32px;margin-bottom:24px;color:white;}\n"
                + ".header-card h1{font-size:28px;margin-bottom:8px;display:flex;align-items:center;gap:12px;}\n"
                + ".header-card .sub{opacity:0.9;font-size:14px;}\n"
                + ".status-badge{display:inline-block;background:rgba(255,255,255,0.2);border-radius:20px;padding:4px 12px;font-size:12px;margin-left:12px;}\n"
                + "/* 指标卡片 */\n"
                + ".metrics-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:16px;margin-bottom:24px;}\n"
                + ".metric-card{background:#fff;border-radius:12px;padding:20px;box-shadow:0 2px 8px rgba(0,0,0,0.06);transition:transform 0.2s,box-shadow 0.2s;}\n"
                + ".metric-card:hover{transform:translateY(-2px);box-shadow:0 8px 24px rgba(0,0,0,0.12);}\n"
                + ".metric-title{font-size:13px;color:#8c8c8c;margin-bottom:8px;display:flex;align-items:center;gap:6px;}\n"
                + ".metric-value{font-size:28px;font-weight:700;color:#262626;}\n"
                + ".metric-unit{font-size:14px;font-weight:400;color:#8c8c8c;margin-left:4px;}\n"
                + ".metric-sub{font-size:12px;color:#bfbfbf;margin-top:8px;}\n"
                + "/* 统计卡片 */\n"
                + ".stats-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:16px;margin-bottom:24px;}\n"
                + ".stat-card{background:#fff;border-radius:12px;padding:16px 20px;box-shadow:0 2px 8px rgba(0,0,0,0.06);border-left:4px solid #1890ff;}\n"
                + ".stat-number{font-size:32px;font-weight:700;color:#1890ff;}\n"
                + ".stat-label{font-size:13px;color:#8c8c8c;margin-top:4px;}\n"
                + "/* 日志卡片 */\n"
                + ".log-card{background:#fff;border-radius:12px;margin-bottom:24px;box-shadow:0 2px 8px rgba(0,0,0,0.06);overflow:hidden;}\n"
                + ".log-header{display:flex;justify-content:space-between;align-items:center;padding:16px 20px;border-bottom:1px solid #f0f0f0;background:#fafafa;}\n"
                + ".log-header h2{font-size:16px;font-weight:600;color:#1a1a1a;display:flex;align-items:center;gap:8px;}\n"
                + ".log-badge{background:#1890ff;color:white;border-radius:12px;padding:2px 8px;font-size:11px;font-weight:500;}\n"
                + ".log-table{width:100%;border-collapse:collapse;}\n"
                + ".log-table th{background:#f9fafb;padding:12px 16px;text-align:left;font-size:13px;font-weight:600;color:#444;border-bottom:1px solid #eee;}\n"
                + ".log-table td{padding:12px 16px;font-size:13px;color:#333;border-bottom:1px solid #f4f4f4;word-break:break-all;}\n"
                + ".log-table tr:hover td{background:#fafafa;}\n"
                + ".level-error{color:#e53935;font-weight:500;}\n"
                + ".level-warn{color:#fa8c16;font-weight:500;}\n"
                + ".level-gc{color:#c25100;}\n"
                + ".level-long-sms{color:#007bff;}\n"
                + ".level-login{color:#52c41a;}\n"
                + ".level-attack{color:#e53935;font-weight:600;}\n"
                + ".empty{text-align:center;padding:48px 0;color:#bfbfbf;}\n"
                + ".pagination{display:flex;justify-content:center;gap:12px;padding:16px;border-top:1px solid #f0f0f0;}\n"
                + ".page-btn{padding:6px 14px;border:1px solid #d9d9d9;background:#fff;border-radius:6px;cursor:pointer;font-size:13px;transition:all 0.2s;}\n"
                + ".page-btn:hover:not(:disabled){border-color:#1890ff;color:#1890ff;}\n"
                + ".page-btn:disabled{background:#f5f5f5;color:#bfbfbf;cursor:not-allowed;}\n"
                + ".page-info{font-size:13px;color:#666;align-self:center;}\n"
                + ".refresh-btn{background:rgba(255,255,255,0.2);border:1px solid rgba(255,255,255,0.3);color:white;padding:6px 14px;border-radius:6px;cursor:pointer;transition:all 0.2s;}\n"
                + ".refresh-btn:hover{background:rgba(255,255,255,0.3);}\n"
                + ".footer{text-align:center;padding:20px;color:#bfbfbf;font-size:12px;}\n"
                + "@media (max-width:768px){.metrics-grid{grid-template-columns:repeat(2,1fr);}.stats-grid{grid-template-columns:repeat(2,1fr);}}\n"
                + "</style>\n"
                + "</head>\n"
                + "<body>\n"
                + "<div class=\"container\">\n"

                // 头部
                + "<div class=\"header-card\">\n"
                + "<h1>📊 TexCell SMS 服务监控面板 <span class=\"status-badge\" id=\"statusBadge\">● 运行中</span></h1>\n"
                + "<div class=\"sub\">实时监控服务状态、异常日志、系统指标、安全攻击 | 自动刷新间隔10秒</div>\n"
                + "<div style=\"margin-top:16px;display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:12px;\">\n"
                + "<div>🖥️ 访问IP: <span id=\"clientIp\">-</span></div>\n"
                + "<div>⏱️ 已运行: <span id=\"runTime\">-</span></div>\n"
                + "<div>🚀 启动时间: <span id=\"startTime\">-</span></div>\n"
                + "<button class=\"refresh-btn\" onclick=\"load()\">🔄 刷新数据</button>\n"
                + "</div>\n"
                + "</div>\n"

                // 系统指标卡片
                + "<div class=\"metrics-grid\" id=\"metricsGrid\"></div>\n"

                // 统计卡片
                + "<div class=\"stats-grid\" id=\"statsGrid\"></div>\n"

                // 安全攻击监控 → 我加在这里
                + "<div class=\"log-card\">\n"
                + "<div class=\"log-header\"><h2>🛡️ 安全攻击监控 <span class=\"log-badge\" style='background:#e53935'>实时防护</span></h2></div>\n"
                + "<table class=\"log-table\">\n"
                + "<thead><tr><th>时间</th><th>攻击IP</th><th>攻击类型</th><th>目标地址</th><th>详情</th></tr></thead>\n"
                + "<tbody id=\"attackLogBody\"></tbody>\n"
                + "</table>\n"
                + "<div class=\"pagination\" id=\"attackPagination\"></div>\n"
                + "</div>\n"

                // 长短信监控
                + "<div class=\"log-card\">\n"
                + "<div class=\"log-header\"><h2>📨 长短信发送监控 <span class=\"log-badge\">实时</span></h2></div>\n"
                + "<table class=\"log-table\"><thead><tr><th style=\"width:100%\">发送记录</th></tr></thead><tbody id=\"longSmsBody\"></tbody></table>\n"
                + "<div class=\"pagination\" id=\"longSmsPagination\"></div>\n"
                + "</div>\n"

                // API调用监控
                + "<div class=\"log-card\">\n"
                + "<div class=\"log-header\"><h2>🌐 API调用监控 <span class=\"log-badge\">最近50条</span></h2></div>\n"
                + "<table class=\"log-table\"><thead><tr><th>时间</th><th>API路径</th><th>耗时(ms)</th><th>状态</th></tr></thead><tbody id=\"apiLogBody\"></tbody></table>\n"
                + "<div class=\"pagination\" id=\"apiPagination\"></div>\n"
                + "</div>\n"

                // 登录行为监控
                + "<div class=\"log-card\">\n"
                + "<div class=\"log-header\"><h2>🔐 登录行为监控 <span class=\"log-badge\">安全审计</span></h2></div>\n"
                + "<table class=\"log-table\"><thead><tr><th>时间</th><th>用户</th><th>结果</th><th>详情</th></tr></thead><tbody id=\"loginLogBody\"></tbody></table>\n"
                + "<div class=\"pagination\" id=\"loginPagination\"></div>\n"
                + "</div>\n"

                // 业务异常监控
                + "<div class=\"log-card\">\n"
                + "<div class=\"log-header\"><h2>⚠️ 业务异常监控 <span class=\"log-badge\">需关注</span></h2></div>\n"
                + "<table class=\"log-table\"><thead><tr><th style=\"width:160px\">时间</th><th style=\"width:120px\">异常类型</th><th style=\"width:200px\">关键说明</th><th>原始日志</th></tr></thead><tbody id=\"errorLogBody\"></tbody></table>\n"
                + "<div class=\"pagination\" id=\"errorPagination\"></div>\n"
                + "</div>\n"

                // GC异常监控
                + "<div class=\"log-card\">\n"
                + "<div class=\"log-header\"><h2>💾 GC异常监控 <span class=\"log-badge\">内存健康</span></h2></div>\n"
                + "<table class=\"log-table\"><thead><tr><th style=\"width:60%\">GC原始日志</th><th style=\"width:40%\">关键说明</th></tr></thead><tbody id=\"gcLogBody\"></tbody></table>\n"
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

                + "function getByteSize(bytes){if(bytes<1024) return bytes+' B';if(bytes<1024*1024) return (bytes/1024).toFixed(2)+' KB';return (bytes/(1024*1024)).toFixed(2)+' MB';}\n"

                + "function getGcNote(line){if(line.includes('Full GC')) return '【严重】Full GC → 全局垃圾回收，会导致服务卡顿';if(line.includes('OutOfMemory')) return '【致命】OOM → 内存溢出，服务可能崩溃';if(line.includes('Allocation Failure')) return '【异常】内存分配失败 → 堆内存不足';if(line.includes('promotion failed')) return '【严重】对象晋升失败 → 老年代空间不足';if(line.includes('concurrent mode failure')) return '【异常】并发回收失败 → 触发Full GC';if(line.includes('Metaspace OOM')) return '【异常】元空间溢出 → 类加载过多';return 'GC异常事件';}\n"

                // 渲染安全攻击日志
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

                + "function renderMetrics(metrics){if(!metrics) return;document.getElementById('metricsGrid').innerHTML='<div class=\"metric-card\"><div class=\"metric-title\">🧠 堆内存使用</div><div class=\"metric-value\">'+formatNumber(metrics.heapUsed)+'<span class=\"metric-unit\">MB</span></div><div class=\"metric-sub\">最大: '+formatNumber(metrics.heapMax)+' MB</div></div>'+'<div class=\"metric-card\"><div class=\"metric-title\">📦 非堆内存</div><div class=\"metric-value\">'+formatNumber(metrics.nonHeapUsed)+'<span class=\"metric-unit\">MB</span></div><div class=\"metric-sub\">已加载类: '+formatNumber(metrics.loadedClasses)+'</div></div>'+'<div class=\"metric-card\"><div class=\"metric-title\">🧵 线程状态</div><div class=\"metric-value\">'+formatNumber(metrics.threadCount)+'<span class=\"metric-unit\">个</span></div><div class=\"metric-sub\">峰值: '+formatNumber(metrics.peakThreadCount)+' | 守护: '+formatNumber(metrics.daemonThreadCount)+'</div></div>'+'<div class=\"metric-card\"><div class=\"metric-title\">⏱️ 系统负载</div><div class=\"metric-value\">'+(metrics.systemLoadAverage||0).toFixed(2)+'<span class=\"metric-unit\"></span></div><div class=\"metric-sub\">可用处理器: '+formatNumber(metrics.availableProcessors)+'</div></div>';}\n"

                + "function renderStats(stats){if(!stats) return;document.getElementById('statsGrid').innerHTML='<div class=\"stat-card\"><div class=\"stat-number\">'+formatNumber(stats.errorCount)+'</div><div class=\"stat-label\">总异常数</div></div>'+'<div class=\"stat-card\"><div class=\"stat-number\">'+formatNumber(stats.gcCount)+'</div><div class=\"stat-label\">GC异常数</div></div>'+'<div class=\"stat-card\"><div class=\"stat-number\">'+formatNumber(stats.longSmsCount)+'</div><div class=\"stat-label\">长短信记录</div></div>'+'<div class=\"stat-card\"><div class=\"stat-number\">'+formatNumber(stats.apiCallCount)+'</div><div class=\"stat-label\">API调用次数</div></div>'+'<div class=\"stat-card\"><div class=\"stat-number\">'+formatNumber(stats.loginSuccessCount)+'/'+formatNumber(stats.loginFailCount)+'</div><div class=\"stat-label\">登录成功/失败</div></div>';}\n"

                + "function renderLongSmsTable(){const totalPage=Math.ceil(longSmsList.length/PAGE_SIZE);const start=(longSmsPage-1)*PAGE_SIZE;const pageData=longSmsList.slice(start,start+PAGE_SIZE);let html='';if(pageData.length===0)html='<tr><td class=\"empty\">✅ 暂无长短信发送记录</td></tr>';else pageData.forEach(line=>{html+='<tr><td class=\"level-long-sms\">'+escapeHtml(line)+'</td></tr>';});document.getElementById('longSmsBody').innerHTML=html;renderPagination('longSms',longSmsPage,totalPage);}\n"

                + "function renderApiTable(){const totalPage=Math.ceil(apiList.length/PAGE_SIZE);const start=(apiPage-1)*PAGE_SIZE;const pageData=apiList.slice(start,start+PAGE_SIZE);let html='';if(pageData.length===0)html='<tr><td colspan=\"4\" class=\"empty\">暂无API调用记录</td></tr>';else pageData.forEach(line=>{const m=line.match(/^\\[(.*?)\\] \\| (.*?) \\| (\\d+)ms \\| (.*)$/);if(m)html+='<tr><td>'+m[1]+'</td><td><code>'+m[2]+'</code></td><td>'+m[3]+'</td><td>'+(m[4]==='成功'?'<span style=\"color:#52c41a\">✓ '+m[4]+'</span>':'<span style=\"color:#e53935\">✗ '+m[4]+'</span>')+'</td></tr>';else html+='<tr><td colspan=\"4\">'+escapeHtml(line)+'</td></tr>';});document.getElementById('apiLogBody').innerHTML=html;renderPagination('api',apiPage,totalPage);}\n"

                + "function renderLoginTable(){const totalPage=Math.ceil(loginList.length/PAGE_SIZE);const start=(loginPage-1)*PAGE_SIZE;const pageData=loginList.slice(start,start+PAGE_SIZE);let html='';if(pageData.length===0)html='<tr><td colspan=\"4\" class=\"empty\">暂无登录记录</td></tr>';else pageData.forEach(line=>{const m=line.match(/^\\[(.*?)\\] \\| (.*?) \\| (.*?) \\| (.*)$/);if(m)html+='<tr><td>'+m[1]+'</td><td>'+escapeHtml(m[2])+'</td><td class=\"'+(m[3]==='成功'?'level-success':'level-error')+'\">'+m[3]+'</td><td>'+escapeHtml(m[4])+'</td></tr>';else html+='<tr><td colspan=\"4\">'+escapeHtml(line)+'</td></tr>';});document.getElementById('loginLogBody').innerHTML=html;renderPagination('login',loginPage,totalPage);}\n"

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
}