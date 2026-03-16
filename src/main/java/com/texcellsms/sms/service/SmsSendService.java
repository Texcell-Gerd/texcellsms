package com.texcellsms.sms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.texcellsms.sms.mapper.webportal.ScheduleNumberTaskMapper;
import com.texcellsms.sms.mapper.webportal.SmsScheduleSendMapper;
import com.texcellsms.sms.model.DTO.SmsSendRequest;
import com.texcellsms.sms.model.DTO.SmsSendResponse;
import com.texcellsms.sms.model.entity.SmsScheduleSend;
import com.texcellsms.sms.util.IdGenerator;
import com.texcellsms.sms.util.SmsConstants;
import com.texcellsms.sms.util.SmsContentUtil;
import com.texcellsms.sms.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;

@Service
public class SmsSendService {

    @Autowired
    private SmsScheduleSendMapper scheduleSendMapper;

    @Autowired
    private ScheduleNumberTaskMapper scheduleNumberTaskMapper;

    public SmsSendResponse doSend(String subAccount, SmsSendRequest request, HttpServletRequest httpRequest) throws Exception {

        // 预任务校验
        if (StringUtils.isNotEmpty(request.getPreTaskId())) {
            int valid = scheduleNumberTaskMapper.checkPreTaskValid(subAccount, request.getPreTaskId());
            if (valid <= 0) {
                throw new Exception("The number task has expired or does not exist");
            }
        }

        String taskId = StringUtils.isNotEmpty(request.getPreTaskId())
                ? request.getPreTaskId()
                : IdGenerator.generatePreTaskId();

        int calleeCount = 0;
        if (StringUtils.isNotEmpty(request.getPreTaskId())) {
            String userPath = SmsConstants.PHONE_PATH + subAccount + "\\";
            File phoneFile = new File(userPath, request.getPreTaskId() + ".txt");
            if (!phoneFile.exists()) throw new Exception("PreTask file not found");
            try (BufferedReader br = new BufferedReader(new FileReader(phoneFile))) {
                while (br.readLine() != null) calleeCount++;
            }
        } else {
            calleeCount = request.getNumbers().size();
        }

        String content = request.getContent();
        int dcs = SmsContentUtil.calculateDCS(content);
        int splitCount = SmsContentUtil.calculateSplitCount(content);
        int totalCount = calleeCount * splitCount;
        int smLength = content.getBytes(StandardCharsets.UTF_8).length;

        String userTaskPath = SmsConstants.TASK_PATH + subAccount + "\\";
        File taskDir = new File(userTaskPath);
        if (!taskDir.exists()) taskDir.mkdirs();

        Map<String, Object> taskJson = new HashMap<>();
        taskJson.put("TaskId", taskId);
        taskJson.put("UserName", subAccount);
        String caller = StringUtils.isNotEmpty(request.getCaller()) ? request.getCaller() : SmsConstants.DEFAULT_CALLER;
        taskJson.put("Caller", caller);
        taskJson.put("CallerTon", caller.matches("^\\d+$") ? "1" : "5");
        taskJson.put("CallerNpi", caller.matches("^\\d+$") ? "1" : "0");
        taskJson.put("DCS", String.valueOf(dcs));
        taskJson.put("UDHIFlag", splitCount > 1 ? "1" : "0");
        taskJson.put("PKSN", splitCount > 1 ? "1" : "0");
        taskJson.put("PKTotal", String.valueOf(splitCount));

        List<Map<String, String>> msgList = new ArrayList<>();
        if (splitCount > 1) {
            int splitChar = SmsContentUtil.getSplitCharCount(dcs);
            for (int i = 0; i < splitCount; i++) {
                int start = i * splitChar;
                int end = Math.min(start + splitChar, content.length());
                String part = content.substring(start, end);
                Map<String, String> msg = new HashMap<>();
                msg.put("PKNO", String.valueOf(i + 1));
                msg.put("MessageText", bytesToHex(part.getBytes(StandardCharsets.UTF_8)));
                msgList.add(msg);
            }
        } else {
            Map<String, String> msg = new HashMap<>();
            msg.put("PKNO", "1");
            msg.put("MessageText", bytesToHex(content.getBytes(StandardCharsets.UTF_8)));
            msgList.add(msg);
        }
        taskJson.put("Msg", msgList);

        File taskFile = new File(taskDir, taskId + ".tmp");
        try (FileWriter fw = new FileWriter(taskFile)) {
            new ObjectMapper().writeValue(fw, taskJson);
        }
        Files.move(taskFile.toPath(), new File(taskDir, taskId + ".txt").toPath(), StandardCopyOption.REPLACE_EXISTING);

        // ==============================================
        // ✅ 强制东八区时间 + 无毫秒
        // ==============================================
        SmsScheduleSend scheduleSend = new SmsScheduleSend();
        scheduleSend.setTaskId(taskId);

        // 强制使用北京时间
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        String nowStr = sdf.format(new Date());
        Date cleanDate = sdf.parse(nowStr);

        // 设置发送时间
        if (StringUtils.isNotEmpty(request.getScheduleTime())) {
            scheduleSend.setScheduleSendTime(sdf.parse(request.getScheduleTime()));
        } else {
            scheduleSend.setScheduleSendTime(cleanDate);
        }

        // 强制状态 0
        scheduleSend.setScheduleStatus((byte) 0);

        scheduleSend.setCalleeCount(calleeCount);
        scheduleSend.setSplitCount(splitCount);
        scheduleSend.setTotalCount(totalCount);
        scheduleSend.setTaskTime(cleanDate);
        scheduleSend.setTaskIp(TokenService.getIpAddress(httpRequest));
        scheduleSend.setSubAccount(subAccount);
        scheduleSend.setTaskTimeZone(SmsConstants.DEFAULT_TIMEZONE);
        scheduleSend.setShortMessage(content);
        scheduleSend.setTaskName(StringUtils.isEmpty(request.getTaskName()) ? "Default Task" : request.getTaskName());
        scheduleSend.setSubmitSuccCount(0);
        scheduleSend.setSubmitFailCount(0);
        scheduleSend.setDrSuccCount(0);
        scheduleSend.setDrFailCount(0);

        // 插入数据库
        scheduleSendMapper.insertAPIScheduleSend(
                scheduleSend.getTaskId(),
                scheduleSend.getScheduleSendTime(),
                scheduleSend.getScheduleStatus(),
                scheduleSend.getCalleeCount(),
                scheduleSend.getSplitCount(),
                scheduleSend.getTotalCount(),
                scheduleSend.getTaskTime(),
                scheduleSend.getTaskIp(),
                scheduleSend.getSubAccount(),
                scheduleSend.getTaskTimeZone(),
                scheduleSend.getShortMessage(),
                scheduleSend.getTaskName(),
                scheduleSend.getSubmitSuccCount(),
                scheduleSend.getSubmitFailCount(),
                scheduleSend.getDrSuccCount(),
                scheduleSend.getDrFailCount()
        );

        SmsSendResponse response = new SmsSendResponse();
        response.setTaskId(taskId);
        response.setDcs(dcs);
        response.setSmLength(smLength);
        response.setPkTotal(splitCount);
        response.setSplitCount(splitCount);
        return response;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}