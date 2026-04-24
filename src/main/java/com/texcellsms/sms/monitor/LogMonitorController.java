package com.texcellsms.sms.monitor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

@RestController
public class LogMonitorController {

    private static final String MONITOR_SECRET = "dn17ORUwf36Znl";

    // 已按你的内网IP配置好白名单
    private static final String[] INNER_IPS = {
            "127.0.0.1",
            "192.168.126.",
            "192.168.3.",
            "192.168.120.",
            "10.238.250.",
            "192.168.128.1"
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
            result.put("msg", "无权限");
            return result;
        }
        result.put("serviceStatus", "UP");
        result.put("runDuration", LogMonitorTask.getRunDuration());
        result.put("recentErrors", LogMonitorTask.recentErrors);
        result.put("recentGcLogs", LogMonitorTask.recentGcLogs);
        result.put("recentLongSms", LogMonitorTask.recentLongSms);
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
            return "<h1 style='color:red;text-align:center;margin-top:100px;'>无权限访问</h1>";
        }

        return "<!DOCTYPE html>"
                + "<html>"
                + "<head>"
                + "<meta charset=\"UTF-8\">"
                + "<title>JAVA新WEB服务运行监控面板</title>"
                + "<style>"
                + "*{margin:0;padding:0;box-sizing:border-box;}"
                + "body{font-family: 'Segoe UI', system-ui, sans-serif;background:#f0f2f5;padding:20px;}"
                + ".container{max-width:1400px;margin:0 auto;background:#fff;border-radius:12px;box-shadow:0 4px 12px rgba(0,0,0,0.06);padding:28px;}"
                + "h1{font-size:24px;color:#111;margin-bottom:20px;font-weight:600;}"
                + ".status-box{padding:18px 20px;border-radius:10px;background:#e6ffeb;color:#065f46;margin-bottom:28px;display:flex;align-items:center;gap:12px;font-size:15px;}"
                + ".run-time{margin-left: auto; font-weight: 600; color: #111;}"
                + ".log-section{margin-bottom:36px;}"
                + ".log-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:14px;}"
                + ".log-header h2{font-size:17px;color:#222;font-weight:600;}"
                + ".log-table{width:100%;border-collapse:collapse;border:1px solid #eee;border-radius:10px;overflow:hidden;}"
                + ".log-table th{background:#f9fafb;padding:12px 16px;text-align:left;font-size:14px;font-weight:600;color:#444;border-bottom:1px solid #eee;}"
                + ".log-table td{padding:12px 16px;font-size:14px;color:#222;border-bottom:1px solid #f4f4f4;word-break:break-all;white-space:pre-wrap;line-height:1.5;}"
                + ".level-error{color:#e53935;font-weight:600;}"
                + ".level-gc{color:#c25100;font-weight:600;}"
                + ".level-long-sms{color:#007bff;font-weight:600;}"
                + ".gc-note{color:#1f6feb;font-weight:500;}"
                + ".empty{text-align:center;padding:44px 0;color:#888;font-size:15px;}"
                + ".pagination{margin-top:12px;display:flex;gap:8px;justify-content:center;}"
                + ".page-btn{padding:7px 14px;border:1px solid #ddd;background:#fff;border-radius:6px;cursor:pointer;font-size:14px;}"
                + ".page-btn:disabled{background:#f7f7f7;color:#aaa;cursor:not-allowed;}"
                + ".page-info{font-size:14px;color:#555;align-self:center;}"
                + "</style>"
                + "</head>"
                + "<body>"
                + "<div class=\"container\">"
                + "<h1>📊 服务运行监控面板</h1>"

                + "<div class=\"status-box\">"
                + "<span>✅</span>"
                + "<span>Java 服务状态：正常运行中</span>"
                + "<span class=\"run-time\" id=\"runTime\">加载中...</span>"
                + "<span style='margin-left:20px;color:#007bff;'>当前访问IP：<span id='clientIp'>-</span></span>"
                + "</div>"

                + "<div class=\"log-section\">"
                + "<div class=\"log-header\"><h2>📩 长短信发送实时监控</h2></div>"
                + "<table class=\"log-table\">"
                + "<thead><tr><th>长短信记录（用户/条数/内容/时间）</th></tr></thead>"
                + "<tbody id=\"longSmsBody\"></tbody>"
                + "</table>"
                + "<div class=\"pagination\" id=\"longSmsPagination\"></div>"
                + "</div>"

                + "<div class=\"log-section\">"
                + "<div class=\"log-header\"><h2>❌ 最近业务异常（所有异常已自动分类）</h2></div>"
                + "<table class=\"log-table\">"
                + "<thead><tr><th style=\"width:170px;\">时间</th><th style=\"width:150px;\">错误类型</th><th style=\"width:260px;\">关键说明</th><th>原始日志</th></tr></thead>"
                + "<tbody id=\"errorLogBody\"></tbody>"
                + "</table>"
                + "<div class=\"pagination\" id=\"errorPagination\"></div>"
                + "</div>"

                + "<div class=\"log-section\">"
                + "<div class=\"log-header\"><h2>⚠️ GC 异常监控（仅异常）</h2></div>"
                + "<table class=\"log-table\">"
                + "<thead><tr><th style=\"width:55%;\">GC 原始日志</th><th style=\"width:45%;\">关键说明</th></tr></thead>"
                + "<tbody id=\"gcLogBody\"></tbody>"
                + "</table>"
                + "<div class=\"pagination\" id=\"gcPagination\"></div>"
                + "</div>"
                + "</div>"

                + "<script>"
                + "const secret = 'dn17ORUwf36Znl';"
                + "const PAGE_SIZE = 10;"
                + "let errorPage = 1, gcPage = 1, longSmsPage = 1;"
                + "let errorList = [], gcList = [], longSmsList = [];"

                + "function getGcNote(line) {"
                + "  if (line.includes('Full GC')) return '【严重】Full GC → 全局垃圾回收，会导致服务卡顿';"
                + "  if (line.includes('OutOfMemory')) return '【致命】OOM → 内存溢出，服务可能崩溃';"
                + "  if (line.includes('Allocation Failure')) return '【异常】内存分配失败 → 堆内存不足';"
                + "  if (line.includes('promotion failed')) return '【严重】对象晋升失败 → 老年代空间不足';"
                + "  if (line.includes('concurrent mode failure')) return '【异常】并发回收失败 → 触发Full GC';"
                + "  if (line.includes('Metaspace OOM')) return '【异常】元空间溢出 → 类加载过多';"
                + "  return 'GC 异常事件';"
                + "}"

                + "function renderLongSmsTable() {"
                + "  const totalPage = Math.ceil(longSmsList.length / PAGE_SIZE);"
                + "  const start = (longSmsPage - 1) * PAGE_SIZE;"
                + "  const end = start + PAGE_SIZE;"
                + "  const pageData = longSmsList.slice(start, end);"
                + "  let html = '';"
                + "  if(pageData.length === 0) html = '<tr><td class=\"empty\">✅ 暂无长短信发送记录</td></tr>';"
                + "  else {"
                + "    pageData.forEach(line => {"
                + "      html += '<tr><td class=\"level-long-sms\">'+line+'</td></tr>';"
                + "    });"
                + "  }"
                + "  document.getElementById('longSmsBody').innerHTML = html;"
                + "  renderPagination('longSms', longSmsPage, totalPage);"
                + "}"

                + "function renderErrorTable() {"
                + "  const totalPage = Math.ceil(errorList.length / PAGE_SIZE);"
                + "  const start = (errorPage - 1) * PAGE_SIZE;"
                + "  const end = start + PAGE_SIZE;"
                + "  const pageData = errorList.slice(start, end);"
                + "  let html = '';"
                + "  if(pageData.length === 0) html = '<tr><td colspan=\"4\" class=\"empty\">✅ 暂无异常</td></tr>';"
                + "  else {"
                + "    pageData.forEach(line => {"
                + "      const m = line.match(/^\\[(.*?)\\] \\| \\[(.*?)\\] \\| (.*?) \\| (.*)$/);"
                + "      if(m) html += '<tr><td>'+m[1]+'</td><td class=\"level-error\">'+m[2]+'</td><td>'+m[3]+'</td><td>'+m[4]+'</td></tr>';"
                + "      else html += '<tr><td>-</td><td>未知</td><td>-</td><td>'+line+'</td></tr>';"
                + "    });"
                + "  }"
                + "  document.getElementById('errorLogBody').innerHTML = html;"
                + "  renderPagination('error', errorPage, totalPage);"
                + "}"

                + "function renderGcTable() {"
                + "  const totalPage = Math.ceil(gcList.length / PAGE_SIZE);"
                + "  const start = (gcPage - 1) * PAGE_SIZE;"
                + "  const end = start + PAGE_SIZE;"
                + "  const pageData = gcList.slice(start, end);"
                + "  let html = '';"
                + "  if(pageData.length === 0) html = '<tr><td colspan=\"2\" class=\"empty\">✅ GC 运行正常，无异常</td></tr>';"
                + "  else {"
                + "    pageData.forEach(line => {"
                + "      const note = getGcNote(line);"
                + "      html += '<tr>"
                +               "<td class=\"level-gc\">'+line+'</td>"
                +               "<td class=\"gc-note\">'+note+'</td>"
                +             "</tr>';"
                + "    });"
                + "  }"
                + "  document.getElementById('gcLogBody').innerHTML = html;"
                + "  renderPagination('gc', gcPage, totalPage);"
                + "}"

                + "function renderPagination(type, current, total) {"
                + "  const dom = document.getElementById(type + 'Pagination');"
                + "  let html = '';"
                + "  if(total > 1) {"
                + "    html += '<button class=\"page-btn\" onclick=\"changePage(\\''+type+'\\', -1)\" '+(current<=1?'disabled':'')+'>上一页</button>';"
                + "    html += '<span class=\"page-info\">第 '+current+' / '+total+' 页</span>';"
                + "    html += '<button class=\"page-btn\" onclick=\"changePage(\\''+type+'\\', 1)\" '+(current>=total?'disabled':'')+'>下一页</button>';"
                + "  }"
                + "  dom.innerHTML = html;"
                + "}"

                + "function changePage(type, step) {"
                + "  if(type === 'error') { errorPage += step; renderErrorTable(); }"
                + "  else if(type === 'gc') { gcPage += step; renderGcTable(); }"
                + "  else if(type === 'longSms') { longSmsPage += step; renderLongSmsTable(); }"
                + "}"

                + "function load() {"
                + "  fetch('/api/monitor/logs?secret=' + secret)"
                + "    .then(res => res.json())"
                + "    .then(data => {"
                + "      if(data.code === 401 || data.code === 403) return;"
                + "      document.getElementById('runTime').innerText = '已运行：' + data.runDuration;"
                + "      document.getElementById('clientIp').innerText = data.clientIp || '-';"
                + "      errorList = data.recentErrors || [];"
                + "      gcList = data.recentGcLogs || [];"
                + "      longSmsList = data.recentLongSms || [];"
                + "      renderLongSmsTable();"
                + "      renderErrorTable();"
                + "      renderGcTable();"
                + "    });"
                + "}"

                + "load(); setInterval(load, 10000);"
                + "</script>"
                + "</body>"
                + "</html>";
    }
}