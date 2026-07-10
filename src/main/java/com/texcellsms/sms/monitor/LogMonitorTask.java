package com.texcellsms.sms.monitor;

import com.texcellsms.sms.interceptor.SecurityMonitorInterceptor;
import com.texcellsms.sms.mapper.webportal.QueueMonitorLogMapper;
import com.texcellsms.sms.mapper.webportal.SmsQueueMapper;
import com.texcellsms.sms.model.entity.QueueMonitorLog;
import com.texcellsms.sms.util.WeChatRobotUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.*;
import java.lang.management.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class LogMonitorTask {

    // ====================== 服务基础信息 ======================
    public static long START_TIME = System.currentTimeMillis();
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    // ====================== 时间限制配置 ======================
    private static final int MAX_RECORDS_PER_TYPE = 50;      // 每种类型最多保留50条
    private static final long SCAN_TIME_WINDOW_MINUTES = 10; // 只扫描最近10分钟的日志

    // ====================== 日志文件路径配置 ======================
    private static final String BUSINESS_LOG_PATH = "logs/texcell-sms.log";
    private static final String GC_LOG_PATH = "logs/gc.log";

    // ====================== 监控数据存储 ======================
    public static final List<String> recentErrors = new LinkedList<>();
    public static final List<String> recentGcLogs = new LinkedList<>();
    public static final List<String> recentLongSms = new LinkedList<>();
    public static final List<String> recentApiLogs = new LinkedList<>();
    public static final List<String> recentLoginLogs = new LinkedList<>();

    // ====================== 统计数据 ======================
    private static final Map<String, AtomicInteger> apiStats = new HashMap<>();

    // ====================== 正则表达式 ======================
    private static final Pattern LOGIN_USER_PATTERN = Pattern.compile("用户:([^,\\s]+)");
    private static final Pattern LOGIN_RESULT_PATTERN = Pattern.compile("Result Val:(\\d+)");
    private static final Pattern API_PATTERN = Pattern.compile("\\[API调用\\] (GET|POST) (\\S+) +耗时:(\\d+)ms +状态:(\\S+)");
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");
    private static final Pattern IP_PATTERN = Pattern.compile("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b");

    private static final Logger logger = LoggerFactory.getLogger(LogMonitorTask.class);

    @Autowired
    private SmsQueueMapper smsQueueMapper;

    @Autowired
    private QueueMonitorLogMapper queueMonitorLogMapper;

    @Autowired
    private WeChatRobotUtil weChatRobotUtil;

    @PostConstruct
    public void init() {
        START_TIME = System.currentTimeMillis();
        // ✅ 启动时清空所有旧数据
        clearAllData();
    }

    /**
     * 清空所有监控数据
     */
    private void clearAllData() {
        synchronized (recentErrors) { recentErrors.clear(); }
        synchronized (recentGcLogs) { recentGcLogs.clear(); }
        synchronized (recentLongSms) { recentLongSms.clear(); }
        synchronized (recentApiLogs) { recentApiLogs.clear(); }
        synchronized (recentLoginLogs) { recentLoginLogs.clear(); }
        synchronized (apiStats) { apiStats.clear(); }
        System.out.println("监控数据已清空，开始重新扫描...");
    }

    // ====================== 获取运行时长 ======================
    public static String getRunDuration() {
        long ms = System.currentTimeMillis() - START_TIME;
        long sec = ms / 1000;
        long min = sec / 60;
        long hour = min / 60;
        long day = hour / 24;
        return day + "天 " + (hour % 24) + "时 " + (min % 60) + "分 " + (sec % 60) + "秒";
    }

    public static String getStartTimeStr() {
        return DATE_FORMAT.format(new Date(START_TIME));
    }

    // ====================== 获取系统指标 ======================
    public static Map<String, Object> getSystemMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();
        metrics.put("heapUsed", heapUsage.getUsed() / 1024 / 1024);
        metrics.put("heapMax", heapUsage.getMax() / 1024 / 1024);
        metrics.put("nonHeapUsed", nonHeapUsage.getUsed() / 1024 / 1024);

        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        metrics.put("threadCount", threadMXBean.getThreadCount());
        metrics.put("peakThreadCount", threadMXBean.getPeakThreadCount());
        metrics.put("daemonThreadCount", threadMXBean.getDaemonThreadCount());

        ClassLoadingMXBean classLoadingMXBean = ManagementFactory.getClassLoadingMXBean();
        metrics.put("loadedClasses", classLoadingMXBean.getLoadedClassCount());

        OperatingSystemMXBean osMXBean = ManagementFactory.getOperatingSystemMXBean();
        metrics.put("availableProcessors", osMXBean.getAvailableProcessors());

        double cpuUsage = 0;
        try {
            com.sun.management.OperatingSystemMXBean osBean =
                    (com.sun.management.OperatingSystemMXBean) osMXBean;
            double cpuLoad = osBean.getSystemCpuLoad();
            cpuUsage = cpuLoad < 0 ? 0 : Math.round(cpuLoad * 100);
        } catch (Exception e) {
            double loadAvg = osMXBean.getSystemLoadAverage();
            if (loadAvg > 0) {
                cpuUsage = Math.round(loadAvg / osMXBean.getAvailableProcessors() * 100);
            } else {
                cpuUsage = 0;
            }
        }
        metrics.put("cpuUsage", cpuUsage);

        return metrics;
    }

    // ====================== 获取统计数据 ======================
    public static Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();

        stats.put("errorCount", recentErrors.size());
        stats.put("gcCount", recentGcLogs.size());
        stats.put("longSmsCount", recentLongSms.size());
        stats.put("apiCallCount", recentApiLogs.size());

        synchronized (recentLoginLogs) {
            int successCount = 0;
            int failCount = 0;
            for (String log : recentLoginLogs) {
                if (log.contains("成功")) {
                    successCount++;
                } else if (log.contains("失败")) {
                    failCount++;
                }
            }
            stats.put("loginSuccessCount", successCount);
            stats.put("loginFailCount", failCount);
        }

        Map<String, Integer> apiTop = new LinkedHashMap<>();
        synchronized (apiStats) {
            List<Map.Entry<String, AtomicInteger>> list = new ArrayList<>(apiStats.entrySet());
            list.sort((entry1, entry2) -> entry2.getValue().get() - entry1.getValue().get());

            int limit = 0;
            for (Map.Entry<String, AtomicInteger> entry : list) {
                if (limit++ >= 10) break;
                apiTop.put(entry.getKey(), entry.getValue().get());
            }
        }
        stats.put("topApis", apiTop);

        return stats;
    }

    // ====================== 检查日志是否在时间窗口内 ======================
    private boolean isWithinTimeWindow(String logLine) {
        Matcher m = TIMESTAMP_PATTERN.matcher(logLine);
        if (m.find()) {
            try {
                String timeStr = m.group(1);
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                LocalDateTime logTime = LocalDateTime.parse(timeStr, formatter);
                LocalDateTime now = LocalDateTime.now();
                LocalDateTime windowStart = now.minusMinutes(SCAN_TIME_WINDOW_MINUTES);
                return logTime.isAfter(windowStart) || logTime.isEqual(windowStart);
            } catch (Exception e) {
                return true;
            }
        }
        return true;
    }

    // ====================== 判断是否应该忽略的异常 ======================
    private boolean shouldIgnoreException(String logLine) {
        if (logLine == null) return true;
        // 忽略这些无害的异常
        String[] ignorePatterns = {
                "ClientAbortException",
                "Broken pipe",
                "Connection reset",
                "No mapping for GET /.well-known",
                "你的主机中的软件中止了一个已建立的连接",
                "你的主机中的软件中止了一个已建立的连接",
                "SocketException",
                "IOException.*你的主机中的软件"
        };
        for (String pattern : ignorePatterns) {
            if (logLine.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    @Scheduled(fixedRate = 30000)
    public void scanBusinessLog() {
        try {
            File logFile = new File(BUSINESS_LOG_PATH);
            if (!logFile.exists()) return;

            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(logFile), StandardCharsets.UTF_8))) {
                List<String> allLines = new LinkedList<>();
                String line;
                while ((line = br.readLine()) != null) allLines.add(line);

                int start = Math.max(0, allLines.size() - 2000);
                List<String> lastLines = allLines.subList(start, allLines.size());

                String currentLoginUser = null;
                String currentIp = null;

                for (String rawLine : lastLines) {
                    if (rawLine.contains("Failed to validate connection")) continue;

                    // 检查时间窗口
                    if (!isWithinTimeWindow(rawLine)) continue;

                    // API 调用监控
                    Matcher apiMatcher = API_PATTERN.matcher(rawLine);
                    if (apiMatcher.find()) {
                        String method = apiMatcher.group(1);
                        String path = apiMatcher.group(2);
                        String cost = apiMatcher.group(3);
                        String status = apiMatcher.group(4);
                        String log = String.format("[%s] | %s %s | %sms | %s",
                                extractTime(rawLine), method, path, cost, status);
                        addToList(recentApiLogs, log, MAX_RECORDS_PER_TYPE);
                        String apiKey = method + " " + path.split("\\?")[0];
                        apiStats.computeIfAbsent(apiKey, k -> new AtomicInteger()).incrementAndGet();
                        continue;
                    }

                    // 长短信监控
                    if (rawLine.contains("[长短信发送]")) {
                        String log = "[" + extractTime(rawLine) + "] " + rawLine;
                        addToList(recentLongSms, log, MAX_RECORDS_PER_TYPE);
                        continue;
                    }

                    // 登录用户
                    if (rawLine.contains("QueryGWUserPass_With2FA调用开始")) {
                        Matcher m = LOGIN_USER_PATTERN.matcher(rawLine);
                        if (m.find()) currentLoginUser = m.group(1);
                        Matcher ipm = IP_PATTERN.matcher(rawLine);
                        if (ipm.find()) currentIp = ipm.group();
                    }

                    // 登录结果
                    if (rawLine.contains("KEY:Result Val:")) {
                        Matcher m = LOGIN_RESULT_PATTERN.matcher(rawLine);
                        if (m.find() && currentLoginUser != null) {
                            String code = m.group(1);
                            boolean success = "0".equals(code);
                            String result = success ? "成功" : "失败";
                            String log = String.format("[%s] | %s | %s | IP:%s",
                                    extractTime(rawLine), currentLoginUser, result, currentIp);
                            addToList(recentLoginLogs, log, MAX_RECORDS_PER_TYPE);

                            if (!success && currentIp != null) {
                                SecurityMonitorInterceptor.recordLoginFailure(currentIp);
                            }
                        }
                        currentLoginUser = null;
                        currentIp = null;
                    }

                    // ====================== 异常监控 ======================
                    if ((rawLine.contains("ERROR") || rawLine.contains("Exception") || rawLine.contains("Failed"))
                            && !rawLine.contains("INFO") && !rawLine.contains("DEBUG")) {

                        // ✅ 过滤无害异常
                        if (shouldIgnoreException(rawLine)) {
                            continue;
                        }

                        String parsed = parseAndClassifyLog(rawLine);
                        if (parsed != null) {
                            // ✅ 去重检查
                            synchronized (recentErrors) {
                                if (!recentErrors.contains(parsed)) {
                                    addToList(recentErrors, parsed, MAX_RECORDS_PER_TYPE);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("日志扫描异常: " + e.getMessage());
        }
    }

    // ====================== GC日志扫描 ======================
    @Scheduled(fixedRate = 30000)
    public void scanGcLog() {
        try {
            File gcFile = new File(GC_LOG_PATH);
            if (!gcFile.exists()) return;

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(gcFile), StandardCharsets.UTF_8))) {

                List<String> recentLines = new LinkedList<>();
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("CommandLine flags")) continue;
                    recentLines.add(line);
                }

                int start = Math.max(0, recentLines.size() - 500);
                List<String> lastLines = recentLines.subList(start, recentLines.size());

                for (String rawLine : lastLines) {
                    if (!isWithinTimeWindow(rawLine)) continue;

                    boolean isAbnormal = rawLine.contains("Full GC") || rawLine.contains("OutOfMemory") ||
                            rawLine.contains("promotion failed") || rawLine.contains("concurrent mode failure") ||
                            rawLine.contains("Allocation Failure") || rawLine.contains("Metaspace OOM");

                    if (isAbnormal) {
                        String logWithTime = "[" + DATE_FORMAT.format(new Date()) + "] " + rawLine;
                        addToList(recentGcLogs, logWithTime, MAX_RECORDS_PER_TYPE);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("GC日志扫描异常: " + e.getMessage());
        }
    }

    @Scheduled(cron = "0 */10 * * * ?")
    public void scanQueueTimeoutTasks() {
        try {
            logger.info("开始扫描队列任务DR未更新");

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
                    // 这个任务已经不在超时列表中了，说明已恢复
                    queueMonitorLogMapper.updateStatus(taskId, "PROCESSED", "系统自动", LocalDateTime.now());
                    autoClosed++;
                    logger.info("任务 {} 已恢复（DR已更新），自动关闭告警", taskId);
                }
            }

            if (allTasks.isEmpty()) {
                logger.info("未发现DR未更新任务，自动关闭 {} 条告警", autoClosed);
                return;
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

                    // 如果告警未发送过，发送企微
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

            logger.info("扫描完成：新增 {} 条，更新 {} 条，自动关闭 {} 条，企微发送 {} 条",
                    newAlerts, updatedAlerts, autoClosed, weChatAlerts);
        } catch (Exception e) {
            logger.error("扫描DR未更新任务失败", e);
        }
    }

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

            // 调用企微发送
            boolean success = weChatRobotUtil.sendMarkdownMessage(msg);

            if (success) {
                logger.info("企微告警发送成功: taskId={}, level={}", log.getTaskId(), log.getAlertLevel());
            } else {
                logger.warn("企微告警发送失败: taskId={}", log.getTaskId());
            }

        } catch (Exception e) {
            logger.error("发送告警失败", e);
        }
    }


    // ====================== 辅助方法 ======================
    private static void addToList(List<String> list, String item, int maxSize) {
        synchronized (list) {
            // 去重检查
            if (list.contains(item)) return;
            list.add(0, item);
            if (list.size() > maxSize) {
                list.remove(list.size() - 1);
            }
        }
    }

    private static String extractTime(String rawLine) {
        Matcher m = TIMESTAMP_PATTERN.matcher(rawLine);
        if (m.find()) return m.group(1);
        return DATE_FORMAT.format(new Date());
    }

    private static String extractIpFromLog(String rawLine) {
        Matcher m = IP_PATTERN.matcher(rawLine);
        if (m.find()) return m.group();
        return null;
    }

    private String getLoginErrorMsg(String code) {
        switch (code) {
            case "402": return "账号或密码错误";
            case "403": return "认证失败/两步验证获取失败";
            case "405": return "参数不完整";
            case "480": return "IP已锁定（多次失败）";
            case "502": return "CacheDB执行失败";
            case "603": return "账户已锁定";
            default: return "未知登录错误";
        }
    }

    private String parseAndClassifyLog(String rawLine) {
        String time = extractTime(rawLine);

        // 提取关键异常信息（只取第一行）
        String[] lines = rawLine.split("\n");
        String firstLine = lines[0];

        String type = "未知错误";
        String keyInfo = "";
        String shortRaw = "";

        if (firstLine.contains("NullPointerException")) {
            type = "空指针异常";
            keyInfo = "Java代码访问了null对象，请检查变量初始化";
            shortRaw = firstLine.length() > 150 ? firstLine.substring(0, 150) : firstLine;
        } else if (firstLine.contains("SQLException") || firstLine.contains("CommunicationsException")) {
            type = "数据库连接失败";
            keyInfo = "无法连接到MySQL，请检查地址/账号/防火墙";
            shortRaw = firstLine;
        } else if (firstLine.contains("IOException") || firstLine.contains("SocketException")) {
            type = "网络IO异常";
            keyInfo = "网络读写失败，请检查网络环境或服务地址";
            shortRaw = firstLine.length() > 100 ? firstLine.substring(0, 100) : firstLine;
        } else if (firstLine.contains("NoSuchMethodError") || firstLine.contains("ClassNotFoundException")) {
            type = "类/方法缺失";
            keyInfo = "依赖包缺失或版本不匹配，请检查pom.xml依赖";
            shortRaw = firstLine;
        } else if (firstLine.contains("ArrayIndexOutOfBoundsException")) {
            type = "数组越界异常";
            keyInfo = "代码访问了数组不存在的下标，请检查循环或索引";
            shortRaw = firstLine;
        } else if (firstLine.contains("IndexOutOfBoundsException")) {
            type = "索引越界异常";
            keyInfo = "集合或字符串下标越界，请检查索引范围";
            shortRaw = firstLine;
        } else if (firstLine.contains("IllegalArgumentException")) {
            type = "参数非法异常";
            keyInfo = "传入了无效参数，请检查接口调用参数";
            shortRaw = firstLine;
        } else if (firstLine.contains("IllegalStateException")) {
            type = "状态异常";
            keyInfo = "对象状态不符合要求，如流已关闭/会话已过期";
            shortRaw = firstLine;
        } else if (firstLine.contains("TimeoutException")) {
            type = "超时异常";
            keyInfo = "请求超时，请检查服务响应速度或网络";
            shortRaw = firstLine;
        } else if (firstLine.contains("ConnectException")) {
            type = "连接拒绝";
            keyInfo = "对方服务拒绝连接，请检查服务是否启动/端口是否正确";
            shortRaw = firstLine;
        } else if (firstLine.contains("ParseException")) {
            type = "解析异常";
            keyInfo = "日期/JSON/XML解析失败，请检查格式是否正确";
            shortRaw = firstLine;
        } else if (firstLine.contains("NumberFormatException")) {
            type = "数字格式异常";
            keyInfo = "字符串转数字失败，如空字符串/非数字字符";
            shortRaw = firstLine;
        } else if (firstLine.contains("ConcurrentModificationException")) {
            type = "并发修改异常";
            keyInfo = "遍历集合时修改了集合，请检查循环逻辑";
            shortRaw = firstLine;
        } else if (firstLine.contains("StackOverflowError")) {
            type = "栈溢出错误";
            keyInfo = "递归过深或死循环，导致调用栈溢出";
            shortRaw = firstLine;
        } else if (firstLine.contains("OutOfMemoryError")) {
            type = "内存溢出错误";
            keyInfo = "JVM内存不足，请检查内存配置或大对象泄漏";
            shortRaw = firstLine;
        } else {
            // 其他未识别异常，不记录（避免误导）
            return null;
        }

        if (shortRaw.length() > 300) {
            shortRaw = shortRaw.substring(0, 300) + "...";
        }

        return String.format("[%s] | [%s] | %s | %s", time, type, keyInfo, shortRaw);
    }
}