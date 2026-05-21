package com.texcellsms.sms.monitor;

import com.texcellsms.sms.interceptor.SecurityMonitorInterceptor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.*;
import java.lang.management.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LogMonitorTask {

    // ====================== 服务基础信息 ======================
    public static long START_TIME = System.currentTimeMillis();
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

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
    private static final Pattern LOGIN_USER_PATTERN = Pattern.compile("用户: (\\w+)");
    private static final Pattern LOGIN_RESULT_PATTERN = Pattern.compile("KEY:Result Val:(\\d+)");
    private static final Pattern API_PATTERN = Pattern.compile("\\[API调用\\] (GET|POST) (\\S+) 耗时:(\\d+)ms 状态:(\\S+)");
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");
    private static final Pattern IP_PATTERN = Pattern.compile("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b");

    @PostConstruct
    public void init() {
        START_TIME = System.currentTimeMillis();
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
        metrics.put("totalLoadedClasses", classLoadingMXBean.getTotalLoadedClassCount());

        OperatingSystemMXBean osMXBean = ManagementFactory.getOperatingSystemMXBean();
        metrics.put("availableProcessors", osMXBean.getAvailableProcessors());
        metrics.put("systemLoadAverage", osMXBean.getSystemLoadAverage());

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

    // ====================== 业务日志扫描 ======================
    @Scheduled(fixedRate = 10000)
    public void scanBusinessLog() {
        try {
            File logFile = new File(BUSINESS_LOG_PATH);
            if (!logFile.exists()) return;

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(logFile), StandardCharsets.UTF_8))) {

                List<String> allLines = new LinkedList<>();
                String line;
                while ((line = br.readLine()) != null) {
                    allLines.add(line);
                }

                int start = Math.max(0, allLines.size() - 1000);
                List<String> lastLines = allLines.subList(start, allLines.size());

                String currentLoginUser = null;
                String currentLoginResult = null;

                for (String rawLine : lastLines) {

                    if (rawLine.contains("Failed to validate connection")) {
                        continue;
                    }

                    // ====================== API调用监控 ======================
                    Matcher apiMatcher = API_PATTERN.matcher(rawLine);
                    if (apiMatcher.find()) {
                        String method = apiMatcher.group(1);
                        String path = apiMatcher.group(2);
                        String cost = apiMatcher.group(3);
                        String status = apiMatcher.group(4);
                        String log = String.format("[%s] | %s %s | %sms | %s",
                                extractTime(rawLine), method, path, cost, status);

                        if (!recentApiLogs.contains(log)) {
                            addToList(recentApiLogs, log, 200);
                            String apiKey = method + " " + path.split("\\?")[0];
                            apiStats.computeIfAbsent(apiKey, k -> new AtomicInteger()).incrementAndGet();
                        }
                        continue;
                    }

                    // ====================== 长短信监控 ======================
                    if (rawLine.contains("[长短信发送]")) {
                        String log = String.format("[%s] | %s", extractTime(rawLine), rawLine);
                        if (!recentLongSms.contains(log)) {
                            addToList(recentLongSms, log, 100);
                        }
                        continue;
                    }

                    // ====================== 登录监控 ======================
                    if (rawLine.contains("QueryGWUserPass_With2FA调用开始")) {
                        Matcher m = LOGIN_USER_PATTERN.matcher(rawLine);
                        if (m.find()) currentLoginUser = m.group(1);
                    }

                    if (rawLine.contains("KEY:Result Val:")) {
                        Matcher m = LOGIN_RESULT_PATTERN.matcher(rawLine);
                        if (m.find()) currentLoginResult = m.group(1);

                        if (currentLoginUser != null && currentLoginResult != null) {
                            boolean isSuccess = "0".equals(currentLoginResult);
                            String result = isSuccess ? "成功" : "失败";
                            String errMsg = isSuccess ? "" : ("(" + getLoginErrorMsg(currentLoginResult) + ")");
                            String log = String.format("[%s] | %s | %s | 登录%s %s",
                                    extractTime(rawLine), currentLoginUser, result, result, errMsg);

                            if (!recentLoginLogs.contains(log)) {
                                addToList(recentLoginLogs, log, 200);
                            }

                            // 暴力破解检测 → 统一交给 SecurityMonitorInterceptor
                            if (!isSuccess) {
                                String ip = extractIpFromLog(rawLine);
                                if (ip != null) {
                                    SecurityMonitorInterceptor.recordLoginFailure(ip);
                                }
                            }
                        }
                        currentLoginUser = null;
                        currentLoginResult = null;
                    }

                    // ====================== 异常监控 ======================
                    if ((rawLine.contains("ERROR") || rawLine.contains("Exception") ||
                            rawLine.contains("Failed") || rawLine.contains("错误")) &&
                            !rawLine.contains("INFO") && !rawLine.contains("DEBUG")) {

                        String parsed = parseAndClassifyLog(rawLine);
                        if (!recentErrors.contains(parsed)) {
                            addToList(recentErrors, parsed, 100);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("业务日志扫描异常: " + e.getMessage());
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

                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("CommandLine flags")) continue;

                    boolean isAbnormal = line.contains("Full GC") || line.contains("OutOfMemory") ||
                            line.contains("promotion failed") || line.contains("concurrent mode failure") ||
                            line.contains("Allocation Failure") || line.contains("Metaspace OOM");

                    if (isAbnormal) {
                        String logWithTime = "[" + DATE_FORMAT.format(new Date()) + "] " + line;
                        if (!recentGcLogs.contains(logWithTime)) {
                            addToList(recentGcLogs, logWithTime, 50);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("GC日志扫描异常: " + e.getMessage());
        }
    }

    // ====================== 辅助方法 ======================
    private static void addToList(List<String> list, String item, int maxSize) {
        synchronized (list) {
            if (list.size() >= maxSize) {
                list.remove(0);
            }
            list.add(item);
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
        String type = "未知错误";
        String keyInfo = "";

        if (rawLine.contains("NullPointerException")) {
            type = "空指针异常";
            keyInfo = "Java代码访问了null对象，请检查变量初始化";
        } else if (rawLine.contains("SQLException") || rawLine.contains("CommunicationsException")) {
            type = "数据库连接失败";
            keyInfo = "无法连接到MySQL，请检查地址/账号/防火墙";
        } else if (rawLine.contains("IOException") || rawLine.contains("SocketException")) {
            type = "网络IO异常";
            keyInfo = "网络读写失败，请检查网络环境或服务地址";
        } else if (rawLine.contains("NoSuchMethodError") || rawLine.contains("ClassNotFoundException")) {
            type = "类/方法缺失";
            keyInfo = "依赖包缺失或版本不匹配，请检查pom.xml依赖";
        } else if (rawLine.contains("ArrayIndexOutOfBoundsException")) {
            type = "数组越界异常";
            keyInfo = "代码访问了数组不存在的下标，请检查循环或索引";
        } else if (rawLine.contains("IndexOutOfBoundsException")) {
            type = "索引越界异常";
            keyInfo = "集合或字符串下标越界，请检查索引范围";
        } else if (rawLine.contains("IllegalArgumentException")) {
            type = "参数非法异常";
            keyInfo = "传入了无效参数，请检查接口调用参数";
        } else if (rawLine.contains("IllegalStateException")) {
            type = "状态异常";
            keyInfo = "对象状态不符合要求，如流已关闭/会话已过期";
        } else if (rawLine.contains("TimeoutException")) {
            type = "超时异常";
            keyInfo = "请求超时，请检查服务响应速度或网络";
        } else if (rawLine.contains("ConnectException")) {
            type = "连接拒绝";
            keyInfo = "对方服务拒绝连接，请检查服务是否启动/端口是否正确";
        } else if (rawLine.contains("ParseException")) {
            type = "解析异常";
            keyInfo = "日期/JSON/XML解析失败，请检查格式是否正确";
        } else if (rawLine.contains("NumberFormatException")) {
            type = "数字格式异常";
            keyInfo = "字符串转数字失败，如空字符串/非数字字符";
        } else if (rawLine.contains("ConcurrentModificationException")) {
            type = "并发修改异常";
            keyInfo = "遍历集合时修改了集合，请检查循环逻辑";
        } else if (rawLine.contains("StackOverflowError")) {
            type = "栈溢出错误";
            keyInfo = "递归过深或死循环，导致调用栈溢出";
        } else if (rawLine.contains("OutOfMemoryError")) {
            type = "内存溢出错误";
            keyInfo = "JVM内存不足，请检查内存配置或大对象泄漏";
        } else if (rawLine.contains("登录") || rawLine.contains("Login")) {
            type = "登录异常";
            keyInfo = "用户登录失败，请检查账号密码或权限";
        } else if (rawLine.contains("短信") || rawLine.contains("SMS")) {
            type = "短信异常";
            keyInfo = "短信发送或接收失败，请检查运营商配置";
        }

        String shortRaw = rawLine.length() > 200 ? rawLine.substring(0, 200) + "..." : rawLine;
        return String.format("[%s] | [%s] | %s | %s", time, type, keyInfo, shortRaw);
    }
}