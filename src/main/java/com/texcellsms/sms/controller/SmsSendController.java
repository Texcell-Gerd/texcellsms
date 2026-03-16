package com.texcellsms.sms.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.texcellsms.sms.model.DTO.SmsSendRequest;
import com.texcellsms.sms.model.DTO.SmsSendResponse;
import com.texcellsms.sms.service.SmsSendService;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.AjaxResult;
import com.texcellsms.sms.util.NumberFileParser;
import com.texcellsms.sms.util.SmsUtil;
import com.texcellsms.sms.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.util.*;

/**
 * 发送接口：接收所有参数 → 打包JSON → 调用C程序 → 返回结果
 */
@RestController
@RequestMapping("/api/prepay/sms")
public class SmsSendController {

    @Autowired
    private SmsSendService smsSendService;

    @PostMapping("/sendMessage")
    public AjaxResult sendMessage(@RequestBody SmsSendRequest request, HttpServletRequest httpRequest) throws Exception {
        // 1. 获取当前登录用户
        String subAccount = TokenService.getUsername();
        if (StringUtils.isEmpty(subAccount)) {
            return AjaxResult.error("未登录或登录已过期");
        }
        // 2. 校验必填参数
        if (StringUtils.isEmpty(request.getContent())) {
            return AjaxResult.error("短信内容不能为空");
        }
        if (StringUtils.isEmpty(request.getPreTaskId()) && CollectionUtils.isEmpty(request.getNumbers())) {
            return AjaxResult.error("接收号码不能为空");
        }
        // 3. 执行发送逻辑
        SmsSendResponse response = smsSendService.doSend(subAccount, request, httpRequest);
        // 4. 返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("Code", 0);
        result.put("ERROR", "");
        result.put("MsgId", response.getTaskId());
        result.put("Callee", Collections.emptyList());
        result.put("DCS", response.getDcs());
        result.put("SMLength", response.getSmLength());
        result.put("PKTotal", response.getPkTotal());
        result.put("SplitCount", response.getSplitCount());
        return AjaxResult.success(result);
    }
}