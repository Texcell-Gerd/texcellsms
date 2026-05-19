package com.texcellsms.sms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.texcellsms.sms.mapper.webportal.ScheduleNumberTaskMapper;
import com.texcellsms.sms.mapper.webportal.SmsScheduleSendMapper;
import com.texcellsms.sms.model.DTO.SmsSendRequest;
import com.texcellsms.sms.model.DTO.SmsSendResponse;
import com.texcellsms.sms.util.IdGenerator;
import com.texcellsms.sms.util.SmsContentUtil;
import com.texcellsms.sms.util.StringUtils;
import com.texcellsms.sms.util.Utf8ToGsm7;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

@Service
public class SmsSendService {

    public static final String PRE_PHONE_ROOT = "D:\\ScheduleTask\\PhonePre\\";
    public static final String REAL_PHONE_ROOT = "D:\\ScheduleTask\\Phone\\";
    public static final String TASK_ROOT = "D:\\ScheduleTask\\Task\\";

    private static final String DEFAULT_CALLER = "SMSINFO";
    private static final long MAX_FILE_SIZE = 3 * 1024 * 1024;
    // 同时支持前端传的横杠格式和原斜杠格式，兼容所有场景
    private static final String[] DATE_FORMATS = {"yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss"};

    @Autowired
    private SmsScheduleSendMapper scheduleSendMapper;

    @Autowired
    private ScheduleNumberTaskMapper scheduleNumberTaskMapper;

    @Transactional(rollbackFor = Exception.class)
    public SmsSendResponse doSend(String subAccount, SmsSendRequest req, HttpServletRequest r) throws Exception {

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

        if (StringUtils.isEmpty(req.getPreTaskId()) && req.getNumbers() != null && !req.getNumbers().isEmpty()) {
            calleeCount = req.getNumbers().size();
            File realUserDir = new File(REAL_PHONE_ROOT + subAccount + "\\");
            if (!realUserDir.exists()) realUserDir.mkdirs();
            File realFile = new File(realUserDir, taskId + ".txt");

            try (BufferedWriter bw = new BufferedWriter(new FileWriter(realFile))) {
                for (int i = 0; i < req.getNumbers().size(); i++) {
                    bw.write(req.getNumbers().get(i).trim());
                    if (i < req.getNumbers().size() - 1) bw.newLine();
                }
            }

            if (!realFile.exists() || realFile.length() == 0) {
                throw new Exception("Failed to generate manual number file");
            }
        } else if (StringUtils.isNotEmpty(req.getPreTaskId())) {
            preFile = new File(PRE_PHONE_ROOT + subAccount + "\\" + req.getPreTaskId() + ".txt");
            if (!preFile.exists())
                throw new Exception("Uploaded number file does not exist");

            if (preFile.length() > MAX_FILE_SIZE) {
                throw new Exception("File size cannot exceed 3MB");
            }

            calleeCount = countLine(preFile);
            File realDir = new File(REAL_PHONE_ROOT + subAccount + "\\");
            if (!realDir.exists()) realDir.mkdirs();
            File realFile = new File(realDir, taskId + ".txt");
            Files.copy(preFile.toPath(), realFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        if (calleeCount <= 0) {
            throw new Exception("Recipient numbers cannot be empty");
        }

        String content = req.getContent();
        int dcs = SmsContentUtil.calculateDCS(content);

        // 使用正确的分片数计算方法
        int splitCount = SmsContentUtil.calcSplitCountCorrect(content);  // ✅ 改用新方法
        int totalCount = calleeCount * splitCount;


        File taskUserDir = new File(TASK_ROOT + subAccount + "\\");
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

        scheduleSendMapper.insertAPIScheduleSend(
                taskId, scheduleTime, calleeCount, splitCount, totalCount,
                now, TokenService.getIpAddress(r), subAccount, content,
                StringUtils.defaultIfEmpty(req.getTaskName(), "Default"),
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
        List<String> list = new ArrayList<>();
        int dcs = SmsContentUtil.calculateDCS(content);


        if (splitCount == 1) {
            // ✅ 不分片，直接返回原内容
            list.add(content);
            return list;
        }

        if (dcs == 8) {
            // UCS-2: 按字符数切分
            int per = SmsContentUtil.getSplitPerSms(dcs); // 67
            int idx = 0;
            for (int i = 0; i < splitCount; i++) {
                int end = Math.min(idx + per, content.length());
                list.add(content.substring(idx, end));
                idx = end;
            }
        } else {
            // GSM7: 按编码后的字节长度切分（只有超过160字节才需要）
            list = splitGsm7ContentByBytes(content, splitCount);
        }

        return list;
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

    private List<String> splitGsm7ContentByBytes(String content, int expectedParts) {
        List<String> parts = new ArrayList<>();
        int maxBytesPerPart = 153; // 长短信每段最大字节数

        int start = 0;
        while (start < content.length() && parts.size() < expectedParts) {
            int currentBytes = 0;
            int end = start;

            // 找到合适的切分点
            while (end < content.length()) {
                char c = content.charAt(end);
                int charBytes = SmsContentUtil.isExtendedGsmChar(c) ? 2 : 1;

                if (currentBytes + charBytes > maxBytesPerPart) {
                    break;
                }
                currentBytes += charBytes;
                end++;
            }

            if (end == start) {
                end = start + 1;
            }

            parts.add(content.substring(start, end));
            start = end;

        }

        return parts;
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
}