package com.texcellsms.sms.monitor;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LogMonitorTask {

    private static final String BUSINESS_LOG_PATH = "logs/texcell-sms.log";
    private static final String GC_LOG_PATH = "logs/gc.log";

    public static final List<String> recentErrors = new LinkedList<>();
    public static final List<String> recentGcLogs = new LinkedList<>();

    // 登录日志正则
    private static final Pattern LOGIN_USER_PATTERN = Pattern.compile("用户: (\\w+)");
    private static final Pattern LOGIN_RESULT_PATTERN = Pattern.compile("KEY:Result Val:(\\d+)");

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

                int start = Math.max(0, allLines.size() - 500);
                List<String> lastLines = allLines.subList(start, allLines.size());

                // 登录上下文临时变量
                String currentLoginUser = null;
                String currentLoginResult = null;

                for (String rawLine : lastLines) {
                    // ====================== 捕获登录失败（核心新增） ======================
                    if (rawLine.contains("QueryGWUserPass_With2FA调用开始")) {
                        Matcher m = LOGIN_USER_PATTERN.matcher(rawLine);
                        if (m.find()) currentLoginUser = m.group(1);
                    }
                    if (rawLine.contains("KEY:Result Val:")) {
                        Matcher m = LOGIN_RESULT_PATTERN.matcher(rawLine);
                        if (m.find()) currentLoginResult = m.group(1);

                        // 只有 !=0 才捕获
                        if (currentLoginUser != null && currentLoginResult != null && !"0".equals(currentLoginResult)) {
                            String errMsg = getLoginErrorMsg(currentLoginResult);
                            String log = String.format("[登录失败] 用户=%s | 错误码=%s | %s",
                                    currentLoginUser, currentLoginResult, errMsg);

                            if (!recentErrors.contains(log)) {
                                if (recentErrors.size() >= 20) recentErrors.remove(0);
                                recentErrors.add(log);
                            }
                        }
                        // 重置
                        currentLoginUser = null;
                        currentLoginResult = null;
                    }

                    // ====================== 原有异常捕获（保留不动） ======================
                    if ((rawLine.contains("ERROR") || rawLine.contains("Exception") || rawLine.contains("Failed")) && !rawLine.contains("INFO"))  {
                        String parsed = parseAndClassifyLog(rawLine);
                        if (!recentErrors.contains(parsed)) {
                            if (recentErrors.size() >= 20) recentErrors.remove(0);
                            recentErrors.add(parsed);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 不影响主业务
        }
    }

    // ====================== 登录错误码映射（对应系统枚举） ======================
    private String getLoginErrorMsg(String code) {
        if ("402".equals(code)) {
            return "账号或密码错误";
        } else if ("403".equals(code)) {
            return "认证失败/两步验证获取失败";
        } else if ("405".equals(code)) {
            return "参数不完整";
        } else if ("480".equals(code)) {
            return "IP已锁定（多次失败）";
        } else if ("502".equals(code)) {
            return "CacheDB执行失败";
        } else if ("603".equals(code)) {
            return "账户已锁定";
        } else {
            return "未知登录错误";
        }
    }

    @Scheduled(fixedRate = 30000)
    public void scanGcLog() {
        try {
            File gcFile = new File(GC_LOG_PATH);
            if (!gcFile.exists()) return;

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(gcFile), StandardCharsets.UTF_8))) {

                String line;
                while ((line = br.readLine()) != null) {

                    // 1. 先排除掉 JVM 启动参数行
                    if (line.contains("CommandLine flags")) {
                        continue;
                    }

                    // 2. 再判断是否为异常 GC 日志
                    boolean isAbnormal =
                            line.contains("Full GC")
                                    || line.contains("OutOfMemory")
                                    || line.contains("promotion failed")
                                    || line.contains("concurrent mode failure")
                                    || line.contains("Allocation Failure")
                                    || line.contains("Metaspace OOM");

                    if (isAbnormal) {
                        if (!recentGcLogs.contains(line)) {
                            if (recentGcLogs.size() >= 20) {
                                recentGcLogs.remove(0);
                            }
                            recentGcLogs.add(line);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 不影响主业务
        }
    }

    // ====================== 原有异常解析 ======================
    private String parseAndClassifyLog(String rawLine) {
        String time = "";
        Pattern p1 = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");
        Pattern p2 = Pattern.compile("^(\\w{3} \\w{3} \\d{2} \\d{2}:\\d{2}:\\d{2} CST \\d{4})");
        Matcher m1 = p1.matcher(rawLine);
        Matcher m2 = p2.matcher(rawLine);
        if (m1.find()) time = m1.group(1);
        else if (m2.find()) time = m2.group(1);

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
        }

        String shortRaw = rawLine.length() > 150 ? rawLine.substring(0, 150) + "..." : rawLine;
        return String.format("[%s] | [%s] | %s | %s", time, type, keyInfo, shortRaw);
    }
}