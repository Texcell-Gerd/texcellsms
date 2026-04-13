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

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;

@Service
public class SmsSendService {

    public static final String PRE_PHONE_ROOT = "/home/ScheduleTask/PhonePre/";
    public static final String REAL_PHONE_ROOT = "/home/ScheduleTask/Phone/";
    public static final String TASK_ROOT = "/home/ScheduleTask/Task/";

    private static final String DEFAULT_CALLER = "SMSINFO";
    private static final long MAX_FILE_SIZE = 3 * 1024 * 1024;

    @Autowired
    private SmsScheduleSendMapper scheduleSendMapper;

    @Autowired
    private ScheduleNumberTaskMapper scheduleNumberTaskMapper;

    public SmsSendResponse doSend(String subAccount, SmsSendRequest req, HttpServletRequest r) throws Exception {
        if (StringUtils.isNotEmpty(req.getPreTaskId())) {
            int valid = scheduleNumberTaskMapper.checkPreTaskValid(subAccount, req.getPreTaskId());
            if (valid <= 0)
                throw new Exception("Task has expired, please upload the file again");
        }

        String taskId = IdGenerator.generatePreTaskId();
        int calleeCount = 0;

        if (StringUtils.isEmpty(req.getPreTaskId()) && req.getNumbers() != null && !req.getNumbers().isEmpty()) {
            calleeCount = req.getNumbers().size();
            File realUserDir = new File(REAL_PHONE_ROOT + subAccount + "/");
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
            File preFile = new File(PRE_PHONE_ROOT + subAccount + "/" + req.getPreTaskId() + ".txt");
            if (!preFile.exists())
                throw new Exception("Uploaded number file does not exist");

            if (preFile.length() > MAX_FILE_SIZE) {
                throw new Exception("File size cannot exceed 3MB");
            }

            calleeCount = countLine(preFile);
            File realDir = new File(REAL_PHONE_ROOT + subAccount + "/");
            if (!realDir.exists()) realDir.mkdirs();
            File realFile = new File(realDir, taskId + ".txt");
            Files.copy(preFile.toPath(), realFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            preFile.delete();
            scheduleNumberTaskMapper.deletePreTask(subAccount, req.getPreTaskId());
        }

        if (calleeCount <= 0) {
            throw new Exception("Recipient numbers cannot be empty");
        }

        String content = req.getContent();
        int dcs = SmsContentUtil.calculateDCS(content);
        int splitCount = SmsContentUtil.calcSplitCount(content);
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
        try (FileWriter fw = new FileWriter(tmp)) {
            mapper.writeValue(fw, map);
        }
        Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        Date now = new Date();
        Date scheduleTime = StringUtils.isEmpty(req.getScheduleTime()) ? now : sdf.parse(req.getScheduleTime());

        scheduleSendMapper.insertAPIScheduleSend(
                taskId, scheduleTime, calleeCount, splitCount, totalCount,
                now, TokenService.getIpAddress(r), subAccount, content,
                StringUtils.defaultIfEmpty(req.getTaskName(), "Default"),
                0, 0, 0, 0
        );

        SmsSendResponse resp = new SmsSendResponse();
        resp.setTaskId(taskId);
        resp.setDcs(dcs);
        resp.setPkTotal(splitCount);
        resp.setSplitCount(splitCount);
        return resp;
    }

    private List<Map<String, String>> buildMsgParts(String content, int dcs, int splitCount) throws Exception {
        List<Map<String, String>> list = new ArrayList<>();
        List<String> parts = splitContent(content, splitCount);

        String pksn = "01";
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

    private List<String> splitContent(String content, int splitCount) {
        List<String> list = new ArrayList<>();
        int dcs = SmsContentUtil.calculateDCS(content);
        int per = SmsContentUtil.getSplitPerSms(dcs);
        int idx = 0;
        for (int i = 0; i < splitCount; i++) {
            int end = Math.min(idx + per, content.length());
            list.add(content.substring(idx, end));
            idx = end;
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
}