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
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;

@Service
public class SmsSendService {

    // ✅ 测试服务器路径（对应 /home/ScheduleTask）
    private static final String REMOTE_PHONE_PATH = "\\\\192.168.128.247\\home\\ScheduleTask\\Phone\\";
    private static final String REMOTE_TASK_PATH = "\\\\192.168.128.247\\home\\ScheduleTask\\Task\\";

    @Autowired
    private SmsScheduleSendMapper scheduleSendMapper;
    @Autowired
    private ScheduleNumberTaskMapper scheduleNumberTaskMapper;

    public SmsSendResponse doSend(String subAccount, SmsSendRequest request, HttpServletRequest httpRequest) throws Exception {
        if (StringUtils.isNotEmpty(request.getPreTaskId())) {
            int valid = scheduleNumberTaskMapper.checkPreTaskValid(subAccount, request.getPreTaskId());
            if (valid <= 0) {
                throw new Exception("预上传任务已过期，请重新上传");
            }

            File sourceFile = new File(REMOTE_PHONE_PATH + subAccount + "\\" + request.getPreTaskId() + ".txt");
            if (!sourceFile.exists()) {
                throw new Exception("预上传号码文件不存在");
            }
        }

        String taskId = IdGenerator.generatePreTaskId();
        int calleeCount = 0;

        if (StringUtils.isNotEmpty(request.getPreTaskId())) {
            File sourceFile = new File(REMOTE_PHONE_PATH + subAccount + "\\" + request.getPreTaskId() + ".txt");
            try (BufferedReader br = new BufferedReader(new FileReader(sourceFile))) {
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

        File taskDir = new File(REMOTE_TASK_PATH + subAccount + "\\");
        if (!taskDir.exists()) taskDir.mkdirs(); // 自动创建用户目录

        File taskTmp = new File(taskDir, taskId + ".tmp");
        File taskFinal = new File(taskDir, taskId + ".txt");

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

        try (FileWriter fw = new FileWriter(taskTmp)) {
            new ObjectMapper().writeValue(fw, taskJson); // ✅ 已修复 Jackson 写入
        }

        Files.move(taskTmp.toPath(), taskFinal.toPath(), StandardCopyOption.REPLACE_EXISTING);

        if (StringUtils.isNotEmpty(request.getPreTaskId())) {
            File source = new File(REMOTE_PHONE_PATH + subAccount + "\\" + request.getPreTaskId() + ".txt");
            File target = new File(REMOTE_PHONE_PATH + subAccount + "\\" + taskId + ".txt");
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        String nowStr = sdf.format(new Date());
        Date cleanDate = sdf.parse(nowStr);

        SmsScheduleSend send = new SmsScheduleSend();
        send.setTaskId(taskId);
        send.setScheduleSendTime(StringUtils.isNotEmpty(request.getScheduleTime()) ? sdf.parse(request.getScheduleTime()) : cleanDate);
        send.setScheduleStatus((byte) 0);
        send.setCalleeCount(calleeCount);
        send.setSplitCount(splitCount);
        send.setTotalCount(totalCount);
        send.setTaskTime(cleanDate);
        send.setTaskIp(TokenService.getIpAddress(httpRequest));
        send.setSubAccount(subAccount);
        send.setTaskTimeZone(SmsConstants.DEFAULT_TIMEZONE);
        send.setShortMessage(content);
        send.setTaskName(StringUtils.isEmpty(request.getTaskName()) ? "Default Task" : request.getTaskName());
        send.setSubmitSuccCount(0);
        send.setSubmitFailCount(0);
        send.setDrSuccCount(0);
        send.setDrFailCount(0);

        scheduleSendMapper.insertAPIScheduleSend(
                send.getTaskId(), send.getScheduleSendTime(), send.getScheduleStatus(),
                send.getCalleeCount(), send.getSplitCount(), send.getTotalCount(),
                send.getTaskTime(), send.getTaskIp(), send.getSubAccount(),
                send.getTaskTimeZone(), send.getShortMessage(), send.getTaskName(),
                send.getSubmitSuccCount(), send.getSubmitFailCount(),
                send.getDrSuccCount(), send.getDrFailCount()
        );

        SmsSendResponse resp = new SmsSendResponse();
        resp.setTaskId(taskId);
        resp.setDcs(dcs);
        resp.setSmLength(smLength);
        resp.setPkTotal(splitCount);
        resp.setSplitCount(splitCount);
        return resp;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}