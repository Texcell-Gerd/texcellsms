package com.texcellsms.sms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.io.File;
import java.io.FileWriter;
import java.io.LineNumberReader;
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
    private static final int MAX_PHONE_LIMIT = 200000;

    @Autowired
    private SmsScheduleSendMapper scheduleSendMapper;
    @Autowired
    private ScheduleNumberTaskMapper scheduleNumberTaskMapper;

    public SmsSendResponse doSend(String subAccount, SmsSendRequest req, HttpServletRequest r) throws Exception {
        if (StringUtils.isNotEmpty(req.getPreTaskId())) {
            int valid = scheduleNumberTaskMapper.checkPreTaskValid(subAccount, req.getPreTaskId());
            if (valid <= 0) throw new Exception("预任务已过期，请重新上传");
        }

        String taskId = IdGenerator.generatePreTaskId();
        int calleeCount = 0;

        // ====================== 预上传文件路径 ======================
        if (StringUtils.isNotEmpty(req.getPreTaskId())) {
            File preFile = new File(PRE_PHONE_ROOT + subAccount + "/" + req.getPreTaskId() + ".txt");
            if (!preFile.exists()) throw new Exception("预上传号码文件不存在");
            calleeCount = countLine(preFile);
            if (calleeCount > MAX_PHONE_LIMIT) {
                throw new Exception("号码数量超过限制，单次最多允许发送 200000 个号码");
            }
        } else {
            calleeCount = req.getNumbers() == null ? 0 : req.getNumbers().size();
            if (calleeCount > MAX_PHONE_LIMIT) {
                throw new Exception("号码数量超过限制，单次最多允许发送 200000 个号码");
            }
        }

        if (calleeCount <= 0) throw new Exception("号码不能为空");

        String content = req.getContent();
        int dcs = SmsContentUtil.calculateDCS(content);
        int splitCount = SmsContentUtil.calcSplitCount(content);
        int totalCount = calleeCount * splitCount;

        // ====================== 任务目录路径 ======================
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

        try (FileWriter fw = new FileWriter(tmp)) {
            new ObjectMapper().writeValue(fw, map);
        }
        Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);

        // ====================== 正式文件路径 ======================
        if (StringUtils.isNotEmpty(req.getPreTaskId())) {
            File preFile = new File(PRE_PHONE_ROOT + subAccount + "/" + req.getPreTaskId() + ".txt");
            File realDir = new File(REAL_PHONE_ROOT + subAccount + "/");
            if (!realDir.exists()) realDir.mkdirs();
            File realFile = new File(realDir, taskId + ".txt");

            Files.copy(preFile.toPath(), realFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (!realFile.exists() || realFile.length() == 0) {
                throw new Exception("正式号码文件生成失败");
            }

            preFile.delete();
            scheduleNumberTaskMapper.deletePreTask(subAccount, req.getPreTaskId());
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
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
        if (dcs == 0) {
            List<String> parts = splitContent(content, splitCount);
            for (int i = 0; i < parts.size(); i++) {
                byte[] bytes = Utf8ToGsm7.encode(parts.get(i));
                Map<String, String> m = new HashMap<>();
                m.put("PKNO", String.valueOf(i + 1));
                m.put("MessageText", bytesToHex(bytes));
                list.add(m);
            }
        } else {
            List<String> parts = splitContent(content, splitCount);
            for (int i = 0; i < parts.size(); i++) {
                byte[] bytes = parts.get(i).getBytes("UTF-16BE");
                Map<String, String> m = new HashMap<>();
                m.put("PKNO", String.valueOf(i + 1));
                m.put("MessageText", bytesToHex(bytes));
                list.add(m);
            }
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
        try (LineNumberReader lnr = new LineNumberReader(new java.io.FileReader(file))) {
            lnr.skip(Long.MAX_VALUE);
            return lnr.getLineNumber();
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}