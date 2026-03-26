package com.texcellsms.sms.controller;

import com.texcellsms.sms.model.DTO.SmsSendRequest;
import com.texcellsms.sms.model.DTO.SmsSendResponse;
import com.texcellsms.sms.service.SmsSendService;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.AjaxResult;
import com.texcellsms.sms.util.SmsContentUtil;
import com.texcellsms.sms.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api/eccom/sms")
public class SmsSendController {

    @Autowired
    private SmsSendService smsSendService;

    @PostMapping("/sendMessage")
    public AjaxResult sendMessage(@RequestBody SmsSendRequest request, HttpServletRequest httpRequest) throws Exception {
        String subAccount = TokenService.getUsername();
        if (StringUtils.isEmpty(subAccount)) {
            return AjaxResult.error("未登录或登录已过期");
        }
        if (StringUtils.isEmpty(request.getContent())) {
            return AjaxResult.error("短信内容不能为空");
        }
        if (StringUtils.isEmpty(request.getPreTaskId()) && CollectionUtils.isEmpty(request.getNumbers())) {
            return AjaxResult.error("接收号码不能为空");
        }

        SmsSendResponse response = smsSendService.doSend(subAccount, request, httpRequest);

        // ✅ 修复
        int smLength = request.getContent().getBytes("UTF-8").length;

        Map<String, Object> result = new HashMap<>();
        result.put("Code", 0);
        result.put("ERROR", "");
        result.put("MsgId", response.getTaskId());
        result.put("Callee", Collections.emptyList());
        result.put("DCS", response.getDcs());
        result.put("SMLength", smLength);
        result.put("PKTotal", response.getPkTotal());
        result.put("SplitCount", response.getSplitCount());
        return AjaxResult.success(result);
    }
}