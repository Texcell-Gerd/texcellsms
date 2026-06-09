package com.texcellsms.sms.controller;

import CacheDB.NCacheDBLink;
import com.texcellsms.sms.model.DTO.SmsSendRequest;
import com.texcellsms.sms.model.DTO.SmsSendResponse;
import com.texcellsms.sms.model.DTO.SubmitRecordWithStatsDTO;
import com.texcellsms.sms.service.SmsQueueService;
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

    @Autowired
    private SmsQueueService smsQueueService;

    // ====================== 发送短信 ======================
    @PostMapping("/send")
    public AjaxResult sendSms(
            @RequestBody SmsSendRequest request,
            HttpServletRequest httpRequest) {

        try {
            String username = request.getUsername();
            String timestamp = request.getTimestamp();
            String sign = request.getSign();

            // 校验必填
            if (StringUtils.isAnyBlank(username, timestamp, sign)) {
                return AjaxResult.error(400, "Required parameters are missing");
            }

            // 时间戳校验
            long reqTimestamp;
            try {
                reqTimestamp = Long.parseLong(timestamp);
            } catch (NumberFormatException e) {
                return AjaxResult.error(400, "Invalid timestamp format");
            }

            long reqSec;
            if (timestamp.length() == 13) {
                reqSec = reqTimestamp / 1000;
            } else {
                reqSec = reqTimestamp;
            }

            long nowSec = System.currentTimeMillis() / 1000;
            if (Math.abs(nowSec - reqSec) > 300) {
                return AjaxResult.error(401, "Request expired");
            }

            // 获取密码
            String password = getCustomerPassword(username);
            if (StringUtils.isEmpty(password)) {
                return AjaxResult.error(401, "Account does not exist or password error");
            }

            // 验签
            AjaxResult check = CustomerApiSignUtil.verifySign(username, timestamp, sign, password);
            if (check.get("Code") == null || (Integer) check.get("Code") != 0) {
                return AjaxResult.error(401, "Authentication failed");
            }

            // 业务校验
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

    // ====================== 查询回执 ======================
    @PostMapping("/receipt")
    public AjaxResult queryReceipt(@RequestBody ReceiptRequest request) {

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

        try {
            String username = request.getUsername();
            String timestamp = request.getTimestamp();
            String sign = request.getSign();

            if (StringUtils.isAnyBlank(username, timestamp, sign)) {
                return AjaxResult.error(400, "Required parameters are missing");
            }

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
            if (StringUtils.isNotEmpty(request.getStartTime())) start = sdf.parse(request.getStartTime());
            if (StringUtils.isNotEmpty(request.getEndTime())) end = sdf.parse(request.getEndTime());

            // API 专用方法
            SubmitRecordWithStatsDTO stats = submitRecordService.getSubmitRecordWithStatsAPI(
                    username,
                    start, end,
                    request.getCallee(),
                    request.getMcc(),
                    request.getMnc(),
                    request.getInnerReportState(),
                    null,
                    request.getPageNum(),
                    request.getPageSize()
            );

            return AjaxResult.success("Query successful", stats);

        } catch (ParseException e) {
            return AjaxResult.error(400, "Invalid time format. Use yyyy/MM/dd HH:mm:ss");
        } catch (Exception e) {
            e.printStackTrace();
            return AjaxResult.error(500, "Query failed: " + e.getMessage());
        }
    }

    // ====================== 查询余额 ======================
    @PostMapping("/balance")
    public AjaxResult queryBalance(@RequestBody BalanceRequest request) {
        System.out.println("秘钥："+request.getSign());

        try {
            String username = request.getUsername();
            String timestamp = request.getTimestamp();
            String sign = request.getSign();

            if (StringUtils.isAnyBlank(username, timestamp, sign)) {
                return AjaxResult.error(400, "Required parameters are missing");
            }

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

    // ====================== 内部查询余额接口 ======================
    @PostMapping("/internal/balance")
    public AjaxResult internalQueryBalance(@RequestBody InternalBalanceRequest request) {

        try {
            String username = request.getUsername();
            String password = request.getPassword();

            // 校验必填
            if (StringUtils.isAnyBlank(username, password)) {
                return AjaxResult.error(400, "用户名和密码不能为空");
            }

            // 从数据库查询
            String correctPassword = getCustomerPassword(username);
            if (StringUtils.isEmpty(correctPassword)) {
                return AjaxResult.error(401, "用户不存在");
            }

            // 验证密码
            if (!correctPassword.equals(password)) {
                return AjaxResult.error(401, "密码错误");
            }

            // 查询余额
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
            data.put("username", username);
            data.put("balance", String.format("%.4f", balance));
            data.put("credit", String.format("%.4f", credit));
            data.put("queryTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));

            return AjaxResult.success("查询成功", data);

        } catch (Exception e) {
            e.printStackTrace();
            return AjaxResult.error(500, "查询失败: " + e.getMessage());
        }
    }

    // ====================== 根据TaskId查询短信详情 ======================
    @PostMapping("/task/detail")
    public AjaxResult getTaskDetail(@RequestBody TaskDetailRequest request) {

        try {
            String username = request.getUsername();
            String timestamp = request.getTimestamp();
            String sign = request.getSign();
            String taskId = request.getTaskId();

            // 校验必填
            if (StringUtils.isAnyBlank(username, timestamp, sign, taskId)) {
                return AjaxResult.error(400, "Required parameters are missing");
            }

            // 时间戳校验（支持10位/13位）
            long reqTimestamp;
            try {
                reqTimestamp = Long.parseLong(timestamp);
            } catch (NumberFormatException e) {
                return AjaxResult.error(400, "Invalid timestamp format");
            }

            long reqSec = (timestamp.length() == 13) ? reqTimestamp / 1000 : reqTimestamp;
            long nowSec = System.currentTimeMillis() / 1000;
            if (Math.abs(nowSec - reqSec) > 300) {
                return AjaxResult.error(401, "Request expired");
            }

            // 验签
            String password = getCustomerPassword(username);
            AjaxResult check = CustomerApiSignUtil.verifySign(username, timestamp, sign, password);
            if (check.get("Code") == null || (Integer) check.get("Code") != 0) {
                return AjaxResult.error(401, "Authentication failed");
            }

            // 查询任务详情
            Map<String, Object> result = smsQueueService.getQueueTaskDetail(
                    taskId,
                    1,
                    9999,
                    null
            );

            return AjaxResult.success("Query successful", result);

        } catch (Exception e) {
            e.printStackTrace();
            return AjaxResult.error(500, "Query failed: " + e.getMessage());
        }
    }

    private String getCustomerPassword(String username) {
        return apiClientConfig.getPasswordByUsername(username);
    }

    // ====================== 内部 VO ======================
    public static class ReceiptRequest {
        private String username;
        private String timestamp;
        private String sign;
        private String startTime;
        private String endTime;
        private String callee;
        private String mcc;
        private String mnc;
        private String innerReportState;
        private Integer pageNum = 1;
        private Integer pageSize = 20;

        // getter setter
        public String getUsername() {return username;}
        public void setUsername(String username) {this.username = username;}
        public String getTimestamp() {return timestamp;}
        public void setTimestamp(String timestamp) {this.timestamp = timestamp;}
        public String getSign() {return sign;}
        public void setSign(String sign) {this.sign = sign;}
        public String getStartTime() {return startTime;}
        public void setStartTime(String startTime) {this.startTime = startTime;}
        public String getEndTime() {return endTime;}
        public void setEndTime(String endTime) {this.endTime = endTime;}
        public String getCallee() {return callee;}
        public void setCallee(String callee) {this.callee = callee;}
        public String getMcc() {return mcc;}
        public void setMcc(String mcc) {this.mcc = mcc;}
        public String getMnc() {return mnc;}
        public void setMnc(String mnc) {this.mnc = mnc;}
        public String getInnerReportState() {return innerReportState;}
        public void setInnerReportState(String innerReportState) {this.innerReportState = innerReportState;}
        public Integer getPageNum() {return pageNum;}
        public void setPageNum(Integer pageNum) {this.pageNum = pageNum;}
        public Integer getPageSize() {return pageSize;}
        public void setPageSize(Integer pageSize) {this.pageSize = pageSize;}
    }

    public static class BalanceRequest {
        private String username;
        private String timestamp;
        private String sign;

        public String getUsername() {return username;}
        public void setUsername(String username) {this.username = username;}
        public String getTimestamp() {return timestamp;}
        public void setTimestamp(String timestamp) {this.timestamp = timestamp;}
        public String getSign() {return sign;}
        public void setSign(String sign) {this.sign = sign;}
    }

    public static class TaskDetailRequest {
        private String username;
        private String timestamp;
        private String sign;
        private String taskId; // 发送接口返回的MsgId

        // getter setter
        public String getUsername() {return username;}
        public void setUsername(String username) {this.username = username;}
        public String getTimestamp() {return timestamp;}
        public void setTimestamp(String timestamp) {this.timestamp = timestamp;}
        public String getSign() {return sign;}
        public void setSign(String sign) {this.sign = sign;}
        public String getTaskId() {return taskId;}
        public void setTaskId(String taskId) {this.taskId = taskId;}
    }

    public static class InternalBalanceRequest {
        private String username;
        private String password;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}