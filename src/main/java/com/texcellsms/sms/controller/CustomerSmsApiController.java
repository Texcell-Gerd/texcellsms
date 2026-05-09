package com.texcellsms.sms.controller;

import CacheDB.NCacheDBLink;
import com.texcellsms.sms.model.DTO.SmsSendRequest;
import com.texcellsms.sms.model.DTO.SmsSendResponse;
import com.texcellsms.sms.model.DTO.SubmitRecordWithStatsDTO;
import com.texcellsms.sms.service.SmsSendService;
import com.texcellsms.sms.service.SubmitRecordService;
import com.texcellsms.sms.util.AjaxResult;
import com.texcellsms.sms.util.ApiClientConfig;
import com.texcellsms.sms.util.CustomerApiSignUtil;
import com.texcellsms.sms.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/open/api/sms")
public class CustomerSmsApiController {

    @Autowired
    private SmsSendService smsSendService;

    @Autowired
    private SubmitRecordService submitRecordService;

    @Autowired
    private ApiClientConfig apiClientConfig;

    // ====================== Send SMS ======================
    @PostMapping("/send")
    public AjaxResult sendSms(
            @RequestParam String username,
            @RequestParam String timestamp,
            @RequestParam String sign,
            @RequestBody SmsSendRequest request,
            HttpServletRequest httpRequest) {

        try {
            long reqTimestamp;
            try {
                reqTimestamp = Long.parseLong(timestamp);
            } catch (NumberFormatException e) {
                return AjaxResult.error(400, "Invalid timestamp format");
            }

            long now = System.currentTimeMillis() / 1000;
            if (Math.abs(now - reqTimestamp) > 300) {
                return AjaxResult.error(401, "Request expired");
            }

            String password = getCustomerPassword(username);
            if (StringUtils.isEmpty(password)) {
                return AjaxResult.error(401, "Account does not exist or password error");
            }

            AjaxResult check = CustomerApiSignUtil.verifySign(username, timestamp, sign, password);
            if (check.get("Code") == null || (Integer) check.get("Code") != 0) {
                return AjaxResult.error(401, "Authentication failed");
            }

            if (CollectionUtils.isEmpty(request.getNumbers())) {
                return AjaxResult.error(400, "Numbers cannot be empty");
            }
            if (request.getNumbers().size() > 200) {
                return AjaxResult.error(400, "Maximum 200 numbers per request");
            }
            if (StringUtils.isEmpty(request.getContent())) {
                return AjaxResult.error(400, "Content cannot be empty");
            }

            SmsSendResponse response = smsSendService.doSend(username, request, httpRequest);
            Map<String, Object> data = new HashMap<>();
            data.put("MsgId", response.getTaskId());

            return AjaxResult.success("Send successful", data);

        } catch (Exception e) {
            e.printStackTrace();
            return AjaxResult.error(500, "Send failed: " + e.getMessage());
        }
    }

    // ====================== Query Receipt ======================
    @GetMapping("/receipt")
    public AjaxResult queryReceipt(
            @RequestParam String username,
            @RequestParam String timestamp,
            @RequestParam String sign,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String callee,
            @RequestParam(required = false) String mcc,
            @RequestParam(required = false) String mnc,
            @RequestParam(required = false) String innerReportState,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

        try {
            String password = getCustomerPassword(username);
            if (password == null) {
                return AjaxResult.error(401, "Account does not exist");
            }

            AjaxResult check = CustomerApiSignUtil.verifySign(username, timestamp, sign, password);
            if (check.get("Code") == null || (Integer) check.get("Code") != 0) {
                return AjaxResult.error(401, "Authentication failed");
            }

            Date start = null;
            Date end = null;
            if (StringUtils.isNotEmpty(startTime)) start = sdf.parse(startTime);
            if (StringUtils.isNotEmpty(endTime)) end = sdf.parse(endTime);

            // ✅ 正确调用
            SubmitRecordWithStatsDTO stats = submitRecordService.getSubmitRecordWithStatsAPI(
                    username,
                    start, end,
                    callee, mcc, mcc,
                    innerReportState,
                    null,
                    pageNum, pageSize
            );

            return AjaxResult.success("Query successful", stats);

        } catch (ParseException e) {
            return AjaxResult.error(400, "Invalid time format. Use yyyy/MM/dd HH:mm:ss");
        } catch (Exception e) {
            e.printStackTrace();
            return AjaxResult.error(500, "Query failed: " + e.getMessage());
        }
    }

    // ====================== Query Balance ======================
    @GetMapping("/balance")
    public AjaxResult queryBalance(
            @RequestParam String username,
            @RequestParam String timestamp,
            @RequestParam String sign) {

        try {
            String password = getCustomerPassword(username);
            AjaxResult check = CustomerApiSignUtil.verifySign(username, timestamp, sign, password);

            if (check.get("Code") == null || (Integer) check.get("Code") != 0) {
                return AjaxResult.error(401, "Authentication failed");
            }

            NCacheDBLink db = NCacheDBLink.getNCacheDBLink();
            db.InitEnv();
            db.ConnectToCacheDB();
            Map<String, Object> balanceMap = db.prc_MMSIFQueryBalance(username);
            db.Disconnect();
            db.Destroy();

            long balanceRaw = Long.parseLong(balanceMap.get("Balance").toString());
            long creditRaw = Long.parseLong(balanceMap.get("Credit").toString());

            double balance = balanceRaw / 10000.0;
            double credit = creditRaw / 10000.0;

            Map<String, Object> data = new HashMap<>();
            data.put("Balance", String.format("%.4f", balance));
            data.put("Credit", String.format("%.4f", credit));

            return AjaxResult.success("Query successful", data);

        } catch (Exception e) {
            e.printStackTrace();
            return AjaxResult.error(500, "Query failed: " + e.getMessage());
        }
    }

    private String getCustomerPassword(String username) {
        return apiClientConfig.getPasswordByUsername(username);
    }
}