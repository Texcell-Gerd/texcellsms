package com.texcellsms.sms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.texcellsms.sms.mapper.webportal.CustomerNumberFileMapper;
import com.texcellsms.sms.mapper.webportal.ScheduleNumberTaskMapper;
import com.texcellsms.sms.mapper.webportal.SmsScheduleSendMapper;
import com.texcellsms.sms.model.DTO.BatchSmsSendRequest;
import com.texcellsms.sms.model.DTO.SmsSendRequest;
import com.texcellsms.sms.model.DTO.SmsSendResponse;
import com.texcellsms.sms.model.entity.CustomerNumberFile;
import com.texcellsms.sms.model.entity.BatchContentSendRequest;
import com.texcellsms.sms.util.IdGenerator;
import com.texcellsms.sms.util.SmsContentUtil;
import com.texcellsms.sms.util.StringUtils;
import com.texcellsms.sms.util.Utf8ToGsm7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class SmsSendService {

    private static final Logger logger = LoggerFactory.getLogger(SmsSendService.class);
    public static final String PRE_PHONE_ROOT = "/home/WEBSMPP/PhonePre/";
    public static final String REAL_PHONE_ROOT = "/home/WEBSMPP/SchedulePhone/";
    public static final String TASK_ROOT = "/home/WEBSMPP/ScheduleTask/";
    private static final String CUSTOMER_NUMBER_BACKUP_ROOT = "/home/WEBSMPP/CustomerFileNumber/";


    private static final String DEFAULT_CALLER = "SMSINFO";
    private static final long MAX_FILE_SIZE = 3 * 1024 * 1024;

    // ✅ 大文件限制（批量接口使用）- 20MB支持100万号码
    private static final long MAX_FILE_SIZE_BATCH = 20 * 1024 * 1024;
    // 同时支持前端传的横杠格式和原斜杠格式，兼容所有场景
    private static final String[] DATE_FORMATS = {"yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss"};

    @Autowired
    private SmsScheduleSendMapper scheduleSendMapper;

    @Autowired
    private ScheduleNumberTaskMapper scheduleNumberTaskMapper;

    @Autowired
    private CustomerNumberFileMapper customerNumberFileMapper;

    @Transactional(rollbackFor = Exception.class)
    public SmsSendResponse doSend(String subAccount, SmsSendRequest req, HttpServletRequest r) throws Exception {

        // ========== 【日志 1】入口参数 ==========
        logger.info("[doSend-入口] 账号={}, preTaskId={}, numbers数量={}, caller={}, scheduleTime={}, taskName={}",
                subAccount, req.getPreTaskId(),
                req.getNumbers() == null ? 0 : req.getNumbers().size(),
                req.getCaller(), req.getScheduleTime(), req.getTaskName());

        // 【新增】打印切片参数
        logger.info("[doSend-切片参数] 账号={}, SplitFlag={}, SplitCount={}, SplitSleepSecond={}",
                subAccount, req.getSplitFlag(), req.getSplitCount(), req.getSplitSleepSecond());

        String originalContent = req.getContent();

        // ========== 【日志 2】原始内容 + HEX ==========
        logger.info("[doSend-原始内容] 账号={}, 原文=[{}], 长度={}, HEX={}",
                subAccount, originalContent,
                originalContent == null ? 0 : originalContent.length(),
                bytesToHex(originalContent));

        String filteredContent = filterEmoji(originalContent);

        // 检查过滤后是否为空
        if (StringUtils.isEmpty(filteredContent)) {
            logger.warn("[doSend-过滤后为空] 账号={}, 原始内容=[{}]", subAccount, originalContent);
            throw new Exception("短信内容不能为空（表情符号已被过滤）");
        }

        // ========== 【日志 3】过滤后内容 ==========
        logger.info("[doSend-过滤后内容] 账号={}, 内容=[{}], 长度={}",
                subAccount, filteredContent, filteredContent.length());

        // 使用过滤后的内容
        req.setContent(filteredContent);

        if (StringUtils.isNotEmpty(req.getPreTaskId())) {
            int valid = scheduleNumberTaskMapper.checkPreTaskValid(subAccount, req.getPreTaskId());
            if (valid <= 0)
                throw new Exception("Task has expired, please upload the file again");
        }

        String taskId = IdGenerator.generatePreTaskId();
        int calleeCount = 0;
        File preFile = null;
        File realFile = null;

        if (StringUtils.isEmpty(req.getPreTaskId()) && req.getNumbers() != null && !req.getNumbers().isEmpty()) {
            // 校验逻辑
            List<String> validNumbers = new ArrayList<>();
            for (String number : req.getNumbers()) {
                String trimmed = number.trim();
                // 校验：只包含数字，长度 8-21 位（根据你的业务调整）
                if (trimmed.matches("^\\d+$") && trimmed.length() >= 8 && trimmed.length() <= 21) {
                    validNumbers.add(trimmed);
                } else {
                    logger.warn("过滤无效号码: {}", trimmed);
                }
            }

            if (validNumbers.isEmpty()) {
                throw new Exception("No valid recipient numbers found");
            }

            calleeCount = validNumbers.size();
            File realUserDir = new File(REAL_PHONE_ROOT + subAccount + "/");
            if (!realUserDir.exists()) realUserDir.mkdirs();
            realFile = new File(realUserDir, taskId + ".txt");

            try (BufferedWriter bw = new BufferedWriter(new FileWriter(realFile))) {
                for (int i = 0; i < validNumbers.size(); i++) {
                    bw.write(validNumbers.get(i));
                    if (i < validNumbers.size() - 1) bw.newLine();
                }
            }

            if (!realFile.exists() || realFile.length() == 0) {
                throw new Exception("Failed to generate manual number file");
            }

            // ========== 【日志 4】手动号码分支 ==========
            logger.info("[doSend-手动号码分支] 账号={}, taskId={}, 有效号码数={}, 文件={}",
                    subAccount, taskId, calleeCount, realFile.getAbsolutePath());

        } else if (StringUtils.isNotEmpty(req.getPreTaskId())) {
            preFile = new File(PRE_PHONE_ROOT + subAccount + "/" + req.getPreTaskId() + ".txt");
            if (!preFile.exists())
                throw new Exception("Uploaded number file does not exist");

            if (preFile.length() > MAX_FILE_SIZE) {
                throw new Exception("File size cannot exceed 3MB");
            }
            List<String> validatedNumbers = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader(preFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.matches("^\\d+$") && trimmed.length() >= 8 && trimmed.length() <= 21) {
                        validatedNumbers.add(trimmed);
                    } else {
                        logger.warn("preTask文件中过滤无效号码: {}", trimmed);
                    }
                }
            }

            if (validatedNumbers.isEmpty()) {
                throw new Exception("No valid numbers found in pre-task file");
            }

            calleeCount = validatedNumbers.size();
            File realDir = new File(REAL_PHONE_ROOT + subAccount + "/");
            if (!realDir.exists()) realDir.mkdirs();
            realFile = new File(realDir, taskId + ".txt");

            // 写入校验后的号码
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(realFile))) {
                for (int i = 0; i < validatedNumbers.size(); i++) {
                    bw.write(validatedNumbers.get(i));
                    if (i < validatedNumbers.size() - 1) bw.newLine();
                }
            }

            // ========== 【日志 5】preTask 分支 ==========
            logger.info("[doSend-preTask分支] 账号={}, taskId={}, preTaskId={}, 有效号码数={}, 文件={}",
                    subAccount, taskId, req.getPreTaskId(), calleeCount, realFile.getAbsolutePath());
        }

        // ✅ 检查号码数量（两个分支之后统一检查）
        if (calleeCount <= 0) {
            throw new Exception("Recipient numbers cannot be empty");
        }

        // ✅ 备份号码文件（两个分支都会执行）
        if (realFile != null && realFile.exists()) {
            backupNumberFile(realFile, subAccount, taskId, req.getFileName());
        }


        String content = req.getContent();
        int dcs = SmsContentUtil.calculateDCS(content);

        // 使用正确的分片数计算方法
        int splitCount = SmsContentUtil.calcSplitCountCorrect(content);
        int totalCount = calleeCount * splitCount;

        // ========== 【日志 6】DCS 与分片计算（最关键）==========
        logger.info("[doSend-编码计算] 账号={}, taskId={}, DCS={} (0=GSM7,8=UCS2), splitCount={}, calleeCount={}, totalCount={}, content=[{}]",
                subAccount, taskId, dcs, splitCount, calleeCount, totalCount, content);


        File taskUserDir = new File(TASK_ROOT + subAccount + "/");
        if (!taskUserDir.exists()) taskUserDir.mkdirs();
        File tmp = new File(taskUserDir, taskId + ".tmp");
        File dst = new File(taskUserDir, taskId + ".txt");

        String caller = StringUtils.isEmpty(req.getCaller()) ? DEFAULT_CALLER : req.getCaller();
        List<Map<String, String>> msgList = buildMsgParts(content, dcs, splitCount);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("TaskId", taskId);
        map.put("UserName", subAccount);
        map.put("Caller", caller);
        map.put("CallerTon", caller.matches("^\\d+$") ? "1" : "5");
        map.put("CallerNpi", caller.matches("^\\d+$") ? "1" : "0");
        map.put("DCS", String.valueOf(dcs));
        map.put("UDHIFlag", splitCount > 1 ? "1" : "0");
        map.put("PKSN", splitCount > 1 ? "1" : "0");
        map.put("PKTotal", String.valueOf(splitCount));
        map.put("Msg", msgList);

        // ====== 新增：将切片参数写入最终发送的 JSON 中 ======
        // 如果前端没传，给个默认兜底值，防止下游报错
        String splitFlag = StringUtils.isEmpty(req.getSplitFlag()) ? "0" : req.getSplitFlag();
        Integer splitNum = req.getSplitCount() == null ? 5000 : req.getSplitCount();
        Integer splitSleep = req.getSplitSleepSecond() == null ? 600 : req.getSplitSleepSecond();

        map.put("SplitFlag", splitFlag);
        map.put("SplitCount", String.valueOf(splitNum));
        map.put("SplitSleepSecond", String.valueOf(splitSleep));

        // ========== 【日志 7】最终 JSON ==========
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        String finalJson = mapper.writeValueAsString(map);

        logger.info("[doSend-最终JSON] 账号={}, taskId={}, JSON长度={}\n{}",
                subAccount, taskId, finalJson.length(), finalJson);

        try (FileWriter fw = new FileWriter(tmp)) {
            fw.write(finalJson); // 同步写入
        }
        Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        Date now = new Date();
        Date scheduleTime;
        if (StringUtils.isEmpty(req.getScheduleTime())) {
            scheduleTime = now;
        } else {
            scheduleTime = parseDate(req.getScheduleTime());
        }

        // TaskName 限制处理
        final int TASK_NAME_MAX_LEN = 64;
        String rawTaskName = req.getTaskName();
        String taskName = StringUtils.defaultIfEmpty(rawTaskName, "Default").trim();

        if (taskName.length() > TASK_NAME_MAX_LEN) {
            logger.warn("TaskName 超长，已截断。原长={}, 值={}", taskName.length(), taskName);
            taskName = taskName.substring(0, TASK_NAME_MAX_LEN);
        }

        scheduleSendMapper.insertAPIScheduleSend(
                taskId, scheduleTime, calleeCount, splitCount, totalCount,
                now, TokenService.getIpAddress(r), subAccount, content,
                taskName,
                0, 0, 0, 0
        );

        if (StringUtils.isNotEmpty(req.getPreTaskId()) && preFile != null && preFile.exists()) {
            preFile.delete();
            scheduleNumberTaskMapper.deletePreTask(subAccount, req.getPreTaskId());
        }

        SmsSendResponse resp = new SmsSendResponse();
        resp.setTaskId(taskId);
        resp.setDcs(dcs);
        resp.setPkTotal(splitCount);
        resp.setSplitCount(splitCount);


        // 【长短信监控 - 自动记录】
        if (splitCount > 1) {
            String monitorLog = String.format(
                    "[长短信发送] | 账号=%s | 任务ID=%s | 长短信条数=%d | 总计费条数=%d | 号码数=%d | 内容=%s",
                    subAccount,
                    taskId,
                    splitCount,
                    totalCount,
                    calleeCount,
                    content
            );
            // 输出到日志，供监控扫描
            logger.warn(monitorLog);
        }

        // ========== 【日志 8】返回结果 ==========
        logger.info("[doSend-完成] 账号={}, taskId={}, dcs={}, splitCount={}, calleeCount={}",
                subAccount, taskId, dcs, splitCount, calleeCount);

        return resp;
    }


    @Transactional(rollbackFor = Exception.class)
    public SmsSendResponse doSendBatch(String subAccount, BatchSmsSendRequest req, HttpServletRequest r) throws Exception {

        String originalContent = req.getContent();
        String filteredContent = filterEmoji(originalContent);

        // 检查过滤后是否为空
        if (StringUtils.isEmpty(filteredContent)) {
            throw new Exception("短信内容不能为空（表情符号已被过滤）");
        }

        // 使用过滤后的内容
        req.setContent(filteredContent);

        if (StringUtils.isNotEmpty(req.getPreTaskId())) {
            int valid = scheduleNumberTaskMapper.checkPreTaskValid(subAccount, req.getPreTaskId());
            if (valid <= 0)
                throw new Exception("Task has expired, please upload the file again");
        }

        String taskId = IdGenerator.generatePreTaskId();
        int calleeCount = 0;
        File preFile = null;
        File realFile = null;

        if (StringUtils.isEmpty(req.getPreTaskId()) && req.getNumbers() != null && !req.getNumbers().isEmpty()) {
            // 校验逻辑
            List<String> validNumbers = new ArrayList<>();
            for (String number : req.getNumbers()) {
                String trimmed = number.trim();
                // 校验：只包含数字，长度 8-21 位（根据你的业务调整）
                if (trimmed.matches("^\\d+$") && trimmed.length() >= 8 && trimmed.length() <= 21) {
                    validNumbers.add(trimmed);
                } else {
                    logger.warn("过滤无效号码: {}", trimmed);
                }
            }

            if (validNumbers.isEmpty()) {
                throw new Exception("No valid recipient numbers found");
            }

            calleeCount = validNumbers.size();
            File realUserDir = new File(REAL_PHONE_ROOT + subAccount + "/");
            if (!realUserDir.exists()) realUserDir.mkdirs();
            realFile = new File(realUserDir, taskId + ".txt");

            try (BufferedWriter bw = new BufferedWriter(new FileWriter(realFile))) {
                for (int i = 0; i < validNumbers.size(); i++) {
                    bw.write(validNumbers.get(i));
                    if (i < validNumbers.size() - 1) bw.newLine();
                }
            }

            if (!realFile.exists() || realFile.length() == 0) {
                throw new Exception("Failed to generate manual number file");
            }
        } else if (StringUtils.isNotEmpty(req.getPreTaskId())) {
            preFile = new File(PRE_PHONE_ROOT + subAccount + "/" + req.getPreTaskId() + ".txt");
            if (!preFile.exists())
                throw new Exception("Uploaded number file does not exist");

            // ========== 唯一改动：使用30MB常量 MAX_FILE_SIZE_BATCH ==========
            if (preFile.length() > MAX_FILE_SIZE_BATCH) {
                throw new Exception("File size cannot exceed 20MB");
            }
            List<String> validatedNumbers = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader(preFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.matches("^\\d+$") && trimmed.length() >= 8 && trimmed.length() <= 21) {
                        validatedNumbers.add(trimmed);
                    } else {
                        logger.warn("preTask文件中过滤无效号码: {}", trimmed);
                    }
                }
            }

            if (validatedNumbers.isEmpty()) {
                throw new Exception("No valid numbers found in pre-task file");
            }

            calleeCount = validatedNumbers.size();
            File realDir = new File(REAL_PHONE_ROOT + subAccount + "/");
            if (!realDir.exists()) realDir.mkdirs();
            realFile = new File(realDir, taskId + ".txt");

            // 写入校验后的号码
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(realFile))) {
                for (int i = 0; i < validatedNumbers.size(); i++) {
                    bw.write(validatedNumbers.get(i));
                    if (i < validatedNumbers.size() - 1) bw.newLine();
                }
            }
        }

        // ✅ 检查号码数量（两个分支之后统一检查）
        if (calleeCount <= 0) {
            throw new Exception("Recipient numbers cannot be empty");
        }

        // ✅ 备份号码文件（两个分支都会执行）
        if (realFile != null && realFile.exists()) {
            backupNumberFile(realFile, subAccount, taskId, req.getFileName());
        }


        String content = req.getContent();
        int dcs = SmsContentUtil.calculateDCS(content);

        // 使用正确的分片数计算方法
        int splitCount = SmsContentUtil.calcSplitCountCorrect(content);
        int totalCount = calleeCount * splitCount;


        File taskUserDir = new File(TASK_ROOT + subAccount + "/");
        if (!taskUserDir.exists()) taskUserDir.mkdirs();
        File tmp = new File(taskUserDir, taskId + ".tmp");
        File dst = new File(taskUserDir, taskId + ".txt");

        String caller = StringUtils.isEmpty(req.getCaller()) ? DEFAULT_CALLER : req.getCaller();
        List<Map<String, String>> msgList = buildMsgParts(content, dcs, splitCount);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("TaskId", taskId);
        map.put("UserName", subAccount);
        map.put("Caller", caller);
        map.put("CallerTon", caller.matches("^\\d+$") ? "1" : "5");
        map.put("CallerNpi", caller.matches("^\\d+$") ? "1" : "0");
        map.put("DCS", String.valueOf(dcs));
        map.put("UDHIFlag", splitCount > 1 ? "1" : "0");
        map.put("PKSN", splitCount > 1 ? "1" : "0");
        map.put("PKTotal", String.valueOf(splitCount));
        map.put("Msg", msgList);

        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        String finalJson = mapper.writeValueAsString(map);

        try (FileWriter fw = new FileWriter(tmp)) {
            fw.write(finalJson); // 同步写入
        }
        Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        Date now = new Date();
        Date scheduleTime;
        if (StringUtils.isEmpty(req.getScheduleTime())) {
            scheduleTime = now;
        } else {
            scheduleTime = parseDate(req.getScheduleTime());
        }

        // TaskName 限制处理
        final int TASK_NAME_MAX_LEN = 64;
        String rawTaskName = req.getTaskName();
        String taskName = StringUtils.defaultIfEmpty(rawTaskName, "Default").trim();

        if (taskName.length() > TASK_NAME_MAX_LEN) {
            logger.warn("TaskName 超长，已截断。原长={}, 值={}", taskName.length(), taskName);
            taskName = taskName.substring(0, TASK_NAME_MAX_LEN);
        }

        scheduleSendMapper.insertAPIScheduleSend(
                taskId, scheduleTime, calleeCount, splitCount, totalCount,
                now, TokenService.getIpAddress(r), subAccount, content,
                taskName,
                0, 0, 0, 0
        );

        if (StringUtils.isNotEmpty(req.getPreTaskId()) && preFile != null && preFile.exists()) {
            preFile.delete();
            scheduleNumberTaskMapper.deletePreTask(subAccount, req.getPreTaskId());
        }

        SmsSendResponse resp = new SmsSendResponse();
        resp.setTaskId(taskId);
        resp.setDcs(dcs);
        resp.setPkTotal(splitCount);
        resp.setSplitCount(splitCount);


        // 【长短信监控 - 自动记录】
        if (splitCount > 1) {
            String monitorLog = String.format(
                    "[长短信发送] | 账号=%s | 任务ID=%s | 长短信条数=%d | 总计费条数=%d | 号码数=%d | 内容=%s",
                    subAccount,
                    taskId,
                    splitCount,
                    totalCount,
                    calleeCount,
                    content
            );
            // 输出到日志，供监控扫描
            org.slf4j.LoggerFactory.getLogger(this.getClass()).warn(monitorLog);
        }
        return resp;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<SmsSendResponse> doSendBatchContent(
            String subAccount, BatchContentSendRequest req, HttpServletRequest r) throws Exception {

        if (req.getNumbers() == null || req.getContents() == null
                || req.getNumbers().size() != req.getContents().size()) {
            throw new Exception("号码与内容数量不一致");
        }
        if (req.getNumbers().isEmpty()) {
            throw new Exception("号码列表不能为空");
        }

        List<SmsSendResponse> results = new ArrayList<>();
        int size = req.getNumbers().size();

        for (int i = 0; i < size; i++) {
            String number = req.getNumbers().get(i);
            String content = req.getContents().get(i);

            if (StringUtils.isEmpty(content)) {
                SmsSendResponse fail = new SmsSendResponse();
                fail.setError("号码 " + number + " 内容为空，跳过");
                results.add(fail);
                continue;
            }

            // 构造单条发送请求，复用 doSend
            SmsSendRequest single = new SmsSendRequest();
            single.setNumbers(Collections.singletonList(number));
            single.setContent(content);
            single.setCaller(req.getCaller());
            single.setScheduleTime(req.getScheduleTime());
            // 每条单独任务名，便于在队列中识别
            String baseName = StringUtils.defaultIfEmpty(req.getTaskName(), "BatchTask");
            String suffix = "-" + (i + 1);
            String tn = baseName + suffix;
            if (tn.length() > 64) tn = tn.substring(0, 64);
            single.setTaskName(tn);

            try {
                results.add(doSend(subAccount, single, r));
            } catch (Exception e) {
                logger.warn("批量发送第 {} 条失败，号码={}，原因={}", i + 1, number, e.getMessage());
                SmsSendResponse fail = new SmsSendResponse();
                fail.setError("号码 " + number + " 发送失败：" + e.getMessage());
                results.add(fail);
            }
        }
        return results;
    }

    private Date parseDate(String dateStr) throws ParseException {
        for (String format : DATE_FORMATS) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(format);
                sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
                return sdf.parse(dateStr);
            } catch (ParseException e) {
            }
        }
        throw new ParseException("Unparseable date: " + dateStr, 0);
    }

    private List<Map<String, String>> buildMsgParts(String content, int dcs, int splitCount) throws Exception {
        List<Map<String, String>> list = new ArrayList<>();
        List<String> parts = splitContent(content, splitCount);


        String pksn = "77";
        String totalParts = String.format("%02X", splitCount);

        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            String currentPart = String.format("%02X", i + 1);
            String udh = "";
            if (splitCount > 1) {
                udh = "050003" + pksn + totalParts + currentPart;
            }

            byte[] contentBytes;
            if (dcs == 0) {
                contentBytes = Utf8ToGsm7.encode(part);
            } else {
                contentBytes = part.getBytes("UTF-16BE");
            }

            String fullHex = udh + bytesToHex(contentBytes);
            Map<String, String> m = new HashMap<>();
            m.put("PKNO", String.valueOf(i + 1));
            m.put("MessageText", fullHex);
            list.add(m);
        }
        return list;
    }

    private List<String> splitContent(String content, int splitCount) throws Exception {
        int dcs = SmsContentUtil.calculateDCS(content);
        if (dcs == 8) {
            List<String> list = new ArrayList<>();
            int per = SmsContentUtil.getSplitPerSms(dcs); // 67
            int idx = 0;
            for (int i = 0; i < splitCount; i++) {
                int end = Math.min(idx + per, content.length());
                list.add(content.substring(idx, end));
                idx = end;
            }
            return list;
        } else {
            return SmsContentUtil.splitGsm7Content(content, splitCount);   // ← 用工具类，别再自己切
        }
    }

    private int countLine(File file) throws Exception {
        int lines = 0;
        try (BufferedReader br = new BufferedReader(new java.io.FileReader(file))) {
            while (br.readLine() != null) lines++;
        }
        return lines;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    /**
     * 字符串转 HEX，用于日志排查乱码（UTF-8 编码后转十六进制）
     */
    private String bytesToHex(String str) {
        if (str == null) return "null";
        return bytesToHex(str.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 过滤 Emoji 和 4字节 Unicode 字符
     */
    private String filterEmoji(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            int codePoint = text.codePointAt(i);
            // 只保留基本多语言平面（BMP）的字符，过滤 Emoji
            if (codePoint <= 0xFFFF) {
                sb.append((char) codePoint);
            } else {
                // 跳过 Emoji（4字节字符）
                i++; // 跳过代理对
            }
        }
        return sb.toString();
    }

    // 清理过期文件的定时任务（每天凌晨1点执行）
    @Scheduled(cron = "0 0 1 * * ?")
    public void cleanExpiredNumberFiles() {
        try {
            LocalDateTime threeDaysAgo = LocalDateTime.now().minusDays(3);

            // 查找3天前的文件记录
            List<CustomerNumberFile> oldFiles = customerNumberFileMapper.selectByTimeBefore(threeDaysAgo);

            for (CustomerNumberFile file : oldFiles) {
                try {
                    // 删除物理文件
                    File f = new File(file.getFilePath());
                    if (f.exists()) {
                        f.delete();
                    }
                    // 删除数据库记录
                    customerNumberFileMapper.deleteById(file.getId());
                } catch (Exception e) {
                    logger.error("删除过期文件失败: {}", file.getFilePath(), e);
                }
            }
        } catch (Exception e) {
            logger.error("清理过期号码文件失败", e);
        }
    }

    private void backupNumberFile(File realFile, String subAccount, String taskId, String originalFileName) {
        try {
            // 创建备份根目录
            File backupRoot = new File(CUSTOMER_NUMBER_BACKUP_ROOT);
            if (!backupRoot.exists()) {
                backupRoot.mkdirs();
            }

            // 创建客户子目录
            File customerBackupDir = new File(CUSTOMER_NUMBER_BACKUP_ROOT + subAccount);
            if (!customerBackupDir.exists()) {
                customerBackupDir.mkdirs();
            }

            // 备份文件名：任务ID_原始文件名.txt
            String backupFileName = taskId + (originalFileName != null ? "_" + originalFileName : "") + ".txt";
            File backupFile = new File(customerBackupDir, backupFileName);

            // 复制号码文件到备份目录
            Files.copy(realFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            // 计算号码数量
            int numberCount = countLine(realFile);

            // 保存记录到数据库
            CustomerNumberFile record = new CustomerNumberFile();
            record.setTaskId(taskId);
            record.setUsername(subAccount);
            record.setFileName(originalFileName != null ? originalFileName : "号码文件");
            record.setFilePath(backupFile.getAbsolutePath());
            record.setFileSize(backupFile.length());
            record.setNumberCount(numberCount);
            record.setCreateTime(LocalDateTime.now());
            customerNumberFileMapper.insert(record);

            logger.info("客户号码文件已备份: 账号={}, 任务ID={}, 号码数={}, 路径={}",
                    subAccount, taskId, numberCount, backupFile.getAbsolutePath());
        } catch (Exception e) {
            // 备份失败不影响主流程，只记录日志
            logger.error("备份客户号码文件失败: 账号={}, 任务ID={}", subAccount, taskId, e);
        }
    }
}