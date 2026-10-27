package com.texcellsms.sms.controller;

import com.texcellsms.sms.model.DTO.SmsSendRequest;
import com.texcellsms.sms.model.DTO.SmsSendResponse;
import com.texcellsms.sms.model.entity.BatchContentSendRequest;
import com.texcellsms.sms.service.FeatureService;
import com.texcellsms.sms.service.SmsSendService;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.AjaxResult;
import com.texcellsms.sms.util.SmsContentUtil;
import com.texcellsms.sms.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api/eccom/sms")
public class SmsSendController {

    private static final Logger logger = LoggerFactory.getLogger(SmsSendController.class);

    @Autowired
    private SmsSendService smsSendService;

    @Autowired
    private FeatureService featureService;

    /**
     * 发送短信接口
     */
    @PostMapping("/sendMessage")
    public AjaxResult sendMessage(@RequestBody SmsSendRequest request, HttpServletRequest httpRequest) throws Exception {
        String subAccount = TokenService.getUsername();
        if (StringUtils.isEmpty(subAccount)) {
            return AjaxResult.error("Not logged in or session expired");
        }
        if (StringUtils.isEmpty(request.getContent())) {
            return AjaxResult.error("SMS content cannot be empty");
        }
        if (StringUtils.isEmpty(request.getPreTaskId()) && CollectionUtils.isEmpty(request.getNumbers())) {
            return AjaxResult.error("Recipient number cannot be empty");
        }

        SmsSendResponse response = smsSendService.doSend(subAccount, request, httpRequest);

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

    @PostMapping("/sendBatchContent")
    public AjaxResult sendBatchContent(@RequestBody BatchContentSendRequest req, HttpServletRequest r) {
        String subAccount = TokenService.getUsername();
        if (StringUtils.isEmpty(subAccount)) {
            return AjaxResult.error("Not logged in");
        }

        // 【权限校验】确保客户有 batch_send_content 模块授权
        if (!featureService.hasAuth(subAccount, "batch_send_content")) {
            return AjaxResult.error("您没有权限使用批量发送功能，请联系管理员开通");
        }

        try {
            List<SmsSendResponse> results = smsSendService.doSendBatchContent(subAccount, req, r);
            return AjaxResult.success(results);
        } catch (Exception e) {
            logger.error("批量发送失败", e);
            return AjaxResult.error(e.getMessage());
        }
    }
}