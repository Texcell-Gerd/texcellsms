package com.texcellsms.sms.monitor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.HashMap;
import java.util.Map;

@RestController
public class LogMonitorController {

    private static final String MONITOR_SECRET = "texcell2025";

    @GetMapping("/api/monitor/logs")
    public Map<String, Object> getLogMonitorData(@RequestParam(required = false) String secret) {
        Map<String, Object> result = new HashMap<>();
        if (!MONITOR_SECRET.equals(secret)) {
            result.put("code", 401);
            result.put("msg", "无权限");
            return result;
        }
        result.put("serviceStatus", "UP");
        result.put("recentErrors", LogMonitorTask.recentErrors);
        result.put("recentGcLogs", LogMonitorTask.recentGcLogs);
        return result;
    }

    @GetMapping("/monitor")
    public String getMonitorPage(@RequestParam(required = false) String secret) {
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
                + "body{font-family: 'Segoe UI', system-ui, sans-serif;background:#f7f8fa;padding:20px;}"
                + ".container{max-width:1400px;margin:0 auto;background:#fff;border-radius:12px;box-shadow:0 2px 10px rgba(0,0,0,0.05);padding:24px;}"
                + "h1{font-size:22px;color:#222;margin-bottom:20px;}"
                + ".status-box{padding:16px 20px;border-radius:8px;background:#e6ffeb;color:#065f46;margin-bottom:24px;display:flex;align-items:center;gap:12px;}"
                + ".log-section{margin-bottom:30px;}"
                + ".log-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:12px;}"
                + ".log-header h2{font-size:16px;color:#333;}"
                + ".log-table{width:100%;border-collapse:collapse;border:1px solid #eee;border-radius:8px;overflow:hidden;}"
                + ".log-table th{background:#f9fafb;padding:10px 14px;text-align:left;font-size:13px;font-weight:500;color:#666;border-bottom:1px solid #eee;}"
                + ".log-table td{padding:10px 14px;font-size:13px;color:#333;border-bottom:1px solid #f4f4f4;word-break:break-all;white-space:pre-wrap;}"
                + ".level-error{color:#e53935;font-weight:600;}"
                + ".level-gc{color:#f57c00;font-weight:600;}"
                + ".empty{text-align:center;padding:40px 0;color:#999;}"
                + ".pagination{margin-top:10px;display:flex;gap:8px;justify-content:center;}"
                + ".page-btn{padding:6px 12px;border:1px solid #ddd;background:#fff;border-radius:4px;cursor:pointer;}"
                + ".page-btn:disabled{background:#f5f5f5;color:#999;cursor:not-allowed;}"
                + ".page-info{font-size:13px;color:#666;align-self:center;}"
                + "</style>"
                + "</head>"
                + "<body>"
                + "<div class=\"container\">"
                + "<h1>📊 服务运行监控</h1>"

                + "<div class=\"status-box\">"
                + "<span>✅</span>"
                + "<span>Java 服务状态：正常运行中</span>"
                + "</div>"

                + "<div class=\"log-section\">"
                + "<div class=\"log-header\"><h2>❌ 最近业务异常（已分类）</h2></div>"
                + "<table class=\"log-table\">"
                + "<thead><tr><th style=\"width:170px;\">时间</th><th style=\"width:150px;\">错误类型</th><th style=\"width:250px;\">关键信息</th><th>原始日志</th></tr></thead>"
                + "<tbody id=\"errorLogBody\"></tbody>"
                + "</table>"
                + "<div class=\"pagination\" id=\"errorPagination\"></div>"
                + "</div>"

                + "<div class=\"log-section\">"
                + "<div class=\"log-header\"><h2>🗑️ 最近 GC 日志</h2></div>"
                + "<table class=\"log-table\">"
                + "<thead><tr><th>GC 内容</th></tr></thead>"
                + "<tbody id=\"gcLogBody\"></tbody>"
                + "</table>"
                + "<div class=\"pagination\" id=\"gcPagination\"></div>"
                + "</div>"
                + "</div>"

                + "<script>"
                + "const secret = 'texcell2025';"
                + "const PAGE_SIZE = 10;"
                + "let errorPage = 1, gcPage = 1;"
                + "let errorList = [], gcList = [];"

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
                + "  if(pageData.length === 0) html = '<tr><td class=\"empty\">✅ GC 正常</td></tr>';"
                + "  else pageData.forEach(line => html += '<tr><td class=\"level-gc\">'+line+'</td></tr>');"
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
                + "  else { gcPage += step; renderGcTable(); }"
                + "}"

                + "function load() {"
                + "  fetch('/api/monitor/logs?secret=' + secret)"
                + "    .then(res => res.json())"
                + "    .then(data => {"
                + "      if(data.code === 401) return;"
                + "      errorList = data.recentErrors || [];"
                + "      gcList = data.recentGcLogs || [];"
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