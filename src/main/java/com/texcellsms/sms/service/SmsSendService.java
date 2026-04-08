package com.texcellsms.sms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.texcellsms.sms.mapper.webportal.ScheduleNumberTaskMapper;
import com.texcellsms.sms.mapper.webportal.SmsScheduleSendMapper;
import com.texcellsms.sms.model.DTO.SmsSendRequest;
import com.texcellsms.sms.model.DTO.SmsSendResponse;
import com.texcellsms.sms.util.IdGenerator;
import com.texcellsms.sms.util.SmsContentUtil;
import com.texcellsms.sms.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger logger = LoggerFactory.getLogger(SmsSendService.class);

    private static final String DEFAULT_CALLER = "SMSINFO";
    private static final long MAX_FILE_SIZE = 3 * 1024 * 1024; // 3MB

    @Autowired
    private SmsScheduleSendMapper scheduleSendMapper;

    @Autowired
    private ScheduleNumberTaskMapper scheduleNumberTaskMapper;

    /**
     * 执行短信发送：预处理号码文件 → 生成任务 → 写入调度文件
     */
    public SmsSendResponse doSend(String subAccount, SmsSendRequest req, HttpServletRequest r) throws Exception {

        logger.info("短信发送请求：subAccount={}, preTaskId={}, numbers.size={}",
                subAccount, req.getPreTaskId(),
                req.getNumbers() == null ? 0 : req.getNumbers().size());

        // 预上传任务校验（保持不变）
        if (StringUtils.isNotEmpty(req.getPreTaskId())) {
            int valid = scheduleNumberTaskMapper.checkPreTaskValid(subAccount, req.getPreTaskId());
            if (valid <= 0)
                throw new Exception("Task has expired, please upload the file again");
        }

        String taskId = IdGenerator.generatePreTaskId();
        int calleeCount = 0;

        // ====================== 【优化核心】分场景处理号码 ======================
        // 场景1：有 preTaskId → 用服务器已上传的文件，完全忽略前端 numbers
        if (StringUtils.isNotEmpty(req.getPreTaskId())) {
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
        // 场景2：无 preTaskId → 用前端传的 numbers 列表生成文件
        else if (req.getNumbers() != null && !req.getNumbers().isEmpty()) {
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
        }

        if (calleeCount <= 0) {
            throw new Exception("Recipient numbers cannot be empty");
        }

        // ====================== 以下代码完全不变 ======================
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

        try (FileWriter fw = new FileWriter(tmp)) {
            new ObjectMapper().writeValue(fw, map);
        }
        Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);

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

    /**
     * 构建短信拆分内容（根据编码生成HEX）
     */
    private List<Map<String, String>> buildMsgParts(String content, int dcs, int splitCount) throws Exception {
        List<Map<String, String>> list = new ArrayList<>();
        List<String> parts = splitContent(content, splitCount);

        if (dcs == 0) {
            for (int i = 0; i < parts.size(); i++) {
                byte[] bytes = parts.get(i).getBytes("ISO-8859-1");
                Map<String, String> m = new HashMap<>();
                m.put("PKNO", String.valueOf(i + 1));
                m.put("MessageText", bytesToHex(bytes));
                list.add(m);
            }
        } else {
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

    /**
     * 按短信长度拆分内容
     */
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

    /**
     * 统计号码文件行数
     */
    private int countLine(File file) throws Exception {
        int lines = 0;
        try (BufferedReader br = new BufferedReader(new java.io.FileReader(file))) {
            while (br.readLine() != null) lines++;
        }
        return lines;
    }

    /**
     * 字节转16进制字符串
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}