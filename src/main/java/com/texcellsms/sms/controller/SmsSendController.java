package com.texcellsms.sms.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.texcellsms.sms.util.NumberFileParser;
import com.texcellsms.sms.util.SmsUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * 发送接口：接收所有参数 → 打包JSON → 调用C程序 → 返回结果
 */
@RestController
@RequestMapping("/api/sms")
public class SmsSendController {

    private static final Logger logger = LoggerFactory.getLogger(SmsSendController.class);

    // C程序实际HTTP接口地址
    private final String C_PROGRAM_URL = "http://127.0.0.1:8080/send";

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 完整提交接口（包含所有参数）
     */
    @PostMapping("/submit")
    public Map<String, Object> Submit(
            @RequestParam(value = "scheduleSendTime", required = false) String scheduleSendTime,
            @RequestParam("taskName") String taskName,
            @RequestParam(value = "calleeText", required = false) String calleeText,
            @RequestParam(value = "numberFile", required = false) MultipartFile numberFile,
            @RequestParam("callerId") String callerId,
            @RequestParam("content") String content
    ) {
        Map<String, Object> result = new HashMap<>();
        try {
            // ========== 1. 参数校验 ==========
            // 必传参数校验
            if (taskName == null || taskName.trim().isEmpty()) {
                result.put("code", 400);
                result.put("msg", "任务名称不能为空");
                return result;
            }
            if (callerId == null || callerId.trim().isEmpty()) {
                result.put("code", 400);
                result.put("msg", "发件人ID不能为空");
                return result;
            }
            if (content == null || content.trim().isEmpty()) {
                result.put("code", 400);
                result.put("msg", "短信内容不能为空");
                return result;
            }

            // 号码来源二选一校验
            List<String> calleeList = new ArrayList<>();
            if (numberFile != null && !numberFile.isEmpty()) {
                // 解析上传的号码文件
                calleeList = NumberFileParser.parseFromFile(numberFile);
            } else if (calleeText != null && !calleeText.trim().isEmpty()) {
                // 解析文本输入的号码
                calleeList = NumberFileParser.parseFromText(calleeText);
            } else {
                result.put("code", 400);
                result.put("msg", "必须提供接收号码（文本）或号码文件");
                return result;
            }

            if (calleeList.isEmpty()) {
                result.put("code", 400);
                result.put("msg", "未解析到有效号码，请检查号码格式");
                return result;
            }

            // ========== 2. 计算短信参数 ==========
            SmsUtil.SmsMeta smsMeta = SmsUtil.calculateMeta(content);

            // ========== 3. 打包传给C程序的JSON ==========
            Map<String, Object> sendJson = new LinkedHashMap<>();
            sendJson.put("TaskID", SmsUtil.generateTaskId());          // 任务ID
            sendJson.put("TaskName", taskName.trim());                 // 任务名称
            sendJson.put("ScheduleSendTime", scheduleSendTime);        // 计划发送时间
            sendJson.put("Caller", callerId.trim());                   // 发件人ID
            sendJson.put("Content", content.trim());                   // 短信内容
            sendJson.put("DCS", smsMeta.getDcs());                     // 编码方式
            sendJson.put("SplitCount", smsMeta.getSplitCount());       // 拆分条数
            sendJson.put("Callee", calleeList);                        // 接收号码列表

            // 打印调试日志
            logger.info("调用C程序参数：{}", objectMapper.writeValueAsString(sendJson));

            // ========== 4. 调用C程序 ==========
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> requestEntity = new HttpEntity<>(objectMapper.writeValueAsString(sendJson), headers);

            // 调用C程序并获取返回结果
            String cResponse = restTemplate.postForObject(C_PROGRAM_URL, requestEntity, String.class);
            if (cResponse == null || cResponse.isEmpty()) {
                throw new RuntimeException("C程序返回结果为空");
            }
            Map<String, Object> cResult = objectMapper.readValue(cResponse, Map.class);

            // ========== 5. 根据C程序结果返回给前端 ==========
            int code = cResult.get("Code") != null ? (int) cResult.get("Code") : -1;
            if (code == 0) {
                result.put("code", 0);
                result.put("msg", "任务提交成功，等待发送");
                result.put("data", cResult);
            } else {
                String errorMsg = cResult.get("Error") != null ? cResult.get("Error").toString() : "未知错误";
                result.put("code", 500);
                result.put("msg", "任务提交失败：" + errorMsg);
                result.put("data", cResult);
            }

        } catch (IllegalArgumentException e) {
            // 参数错误（文件大小、格式等）
            logger.warn("参数校验失败：{}", e.getMessage());
            result.put("code", 400);
            result.put("msg", e.getMessage());
            result.put("data", null);
        } catch (Exception e) {
            // 系统异常
            logger.error("调用C程序异常", e);
            result.put("code", 500);
            result.put("msg", "服务器内部错误：" + e.getMessage());
            result.put("data", null);
        }
        return result;
    }
}