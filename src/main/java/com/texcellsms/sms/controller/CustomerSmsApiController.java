package com.texcellsms.sms.controller;

import CacheDB.NCacheDBLink;
import com.texcellsms.sms.mapper.webportal.SmsQueueMapper;
import com.texcellsms.sms.model.DTO.BatchSmsSendRequest;
import com.texcellsms.sms.model.DTO.SmsSendRequest;
import com.texcellsms.sms.model.DTO.SmsSendResponse;
import com.texcellsms.sms.model.DTO.SubmitRecordWithStatsDTO;
import com.texcellsms.sms.service.SmsQueueService;
import com.texcellsms.sms.service.SmsSendService;
import com.texcellsms.sms.service.SmsUploadService;
import com.texcellsms.sms.service.SubmitRecordService;
import com.texcellsms.sms.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/open/api/sms")
public class CustomerSmsApiController {

    private static final Logger logger = LoggerFactory.getLogger(CustomerSmsApiController.class);

    @Autowired
    private SmsSendService smsSendService;

    @Autowired
    private SubmitRecordService submitRecordService;

    @Autowired
    private ApiClientConfig apiClientConfig;

    @Autowired
    private SmsQueueService smsQueueService;

    @Autowired
    private SmsUploadService smsUploadService;

    @Autowired
    private SmsQueueMapper smsQueueMapper;

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

            long reqSec = (timestamp.length() == 13) ? reqTimestamp / 1000 : reqTimestamp;
            long nowSec = System.currentTimeMillis() / 1000;
            if (Math.abs(nowSec - reqSec) > 300) {
                return AjaxResult.error(401, "Request expired");
            }

            // 获取密码
            String password = getCustomerPassword(username);
            if (StringUtils.isEmpty(password)) {
                return AjaxResult.error(401, "Account does not exist or password error");
            }

            // ✅ 验签
            AjaxResult check = CustomerApiSignUtil.verifySign(username, timestamp, sign, password);
            if (check != null) {
                return check;
            }

            // 业务校验
            if (CollectionUtils.isEmpty(request.getNumbers())) {
                return AjaxResult.error(400, "Numbers cannot be empty");
            }
            if (request.getNumbers().size() > 100000) {
                return AjaxResult.error(400, "Maximum 100000 numbers per request");  // ✅ 修正错误信息
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
        // 添加请求开始日志
        logger.info("========== 查询回执请求开始 ==========");

        try {
            String username = request.getUsername();
            String timestamp = request.getTimestamp();
            String sign = request.getSign();

            // 打印请求参数（敏感信息脱敏）
            logger.info("请求参数 - username: {}, timestamp: {}, pageNum: {}, pageSize: {}, callee: {}, mcc: {}, mnc: {}",
                    username, timestamp, request.getPageNum(), request.getPageSize(),
                    request.getCallee(), request.getMcc(), request.getMnc());

            // 校验必填
            if (StringUtils.isAnyBlank(username, timestamp, sign)) {
                logger.warn("参数缺失 - username: {}, timestamp: {}, sign存在: {}",
                        username, timestamp, sign != null);
                return AjaxResult.error(400, "Required parameters are missing");
            }

            // 时间戳校验
            long reqTimestamp;
            try {
                reqTimestamp = Long.parseLong(timestamp);
            } catch (NumberFormatException e) {
                logger.warn("时间戳格式错误 - username: {}, timestamp: {}", username, timestamp);
                return AjaxResult.error(400, "Invalid timestamp format");
            }
            long reqSec = (timestamp.length() == 13) ? reqTimestamp / 1000 : reqTimestamp;
            long nowSec = System.currentTimeMillis() / 1000;
            if (Math.abs(nowSec - reqSec) > 300) {
                logger.warn("请求过期 - username: {}, requestTime: {}, nowTime: {}", username, reqSec, nowSec);
                return AjaxResult.error(401, "Request expired");
            }

            String password = getCustomerPassword(username);
            if (StringUtils.isEmpty(password)) {
                logger.warn("用户不存在或密码错误 - username: {}", username);
                return AjaxResult.error(401, "Account does not exist or password error");
            }

            // ✅ 验签
            AjaxResult check = CustomerApiSignUtil.verifySign(username, timestamp, sign, password);
            if (check != null) {
                logger.warn("签名验证失败 - username: {}", username);
                return check;
            }
            logger.info("签名验证成功 - username: {}", username);

            // 分页参数保护
            Integer pageNum = request.getPageNum();
            Integer pageSize = request.getPageSize();
            if (pageNum == null || pageNum < 1) pageNum = 1;
            if (pageSize == null || pageSize < 1) pageSize = 10;
            if (pageSize > 1000) pageSize = 1000; // 对外API最大一页1000

            logger.info("分页参数 - pageNum: {}, pageSize: {}", pageNum, pageSize);

            // 时间解析
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
            Date start = null;
            Date end = null;
            if (StringUtils.isNotEmpty(request.getStartTime())) {
                start = sdf.parse(request.getStartTime());
                logger.info("开始时间: {}", request.getStartTime());
            }
            if (StringUtils.isNotEmpty(request.getEndTime())) {
                end = sdf.parse(request.getEndTime());
                logger.info("结束时间: {}", request.getEndTime());
            }

            // 查询
            logger.info("开始查询回执数据 - username: {}, 时间范围: {} ~ {}",
                    username, request.getStartTime(), request.getEndTime());

            SubmitRecordWithStatsDTO stats = submitRecordService.getSubmitRecordWithStatsAPI(
                    username,
                    start, end,
                    request.getCallee(),
                    request.getMcc(),
                    request.getMnc(),
                    request.getInnerReportState(),
                    null,
                    pageNum,
                    pageSize
            );

            // 记录查询结果
            if (stats != null && stats.getRecords() != null) {
                logger.info("查询成功 - username: {}, 总记录数: {}, 当前页: {}, 总页数: {}",
                        username, stats.getSubmitTotal(), stats.getCurrentPage(), stats.getPageCount());
            }

            logger.info("========== 查询回执请求结束 ==========");
            return AjaxResult.success("Query successful", stats);

        } catch (ParseException e) {
            logger.warn("时间格式解析失败 - username: {}, error: {}", request.getUsername(), e.getMessage());
            return AjaxResult.error(400, "Invalid time format. Use yyyy/MM/dd HH:mm:ss");
        } catch (IllegalArgumentException e) {
            // 捕获页码超出异常
            logger.warn("页码超出范围 - username: {}, error: {}", request.getUsername(), e.getMessage());
            return AjaxResult.error(400, e.getMessage());
        } catch (Exception e) {
            logger.error("查询回执异常 - username: {}", request.getUsername(), e);
            return AjaxResult.error(500, "Query failed: " + e.getMessage());
        }
    }

    // ====================== 查询余额 ======================
    @PostMapping("/balance")
    public AjaxResult queryBalance(@RequestBody BalanceRequest request) {
        try {
            // 1. 打印请求参数
            logger.info("收到余额查询请求 - username: {}, timestamp: {}, sign: {}",
                    request.getUsername(), request.getTimestamp(), request.getSign());

            String username = request.getUsername();
            String timestamp = request.getTimestamp();
            String sign = request.getSign();

            if (StringUtils.isAnyBlank(username, timestamp, sign)) {
                logger.warn("参数缺失 - username: {}, timestamp: {}, sign: {}", username, timestamp, sign);
                return AjaxResult.error(400, "Required parameters are missing");
            }

            String password = getCustomerPassword(username);
            logger.info("获取密码 - username: {}, password存在: {}", username, password != null);

            if (StringUtils.isEmpty(password)) {
                logger.warn("用户不存在或密码错误 - username: {}", username);
                return AjaxResult.error(401, "Account does not exist or password error");
            }

            AjaxResult checkSign = CustomerApiSignUtil.verifySign(username, timestamp, sign, password);
            if (checkSign != null) {
                logger.warn("签名验证失败 - username: {}", username);
                return checkSign;
            }

            NCacheDBLink db = null;
            try {
                db = NCacheDBLink.getNCacheDBLink();
                db.InitEnv();
                db.ConnectToCacheDB();

                logger.info("开始查询数据库 - username: {}", username);
                Map<String, Object> balanceMap = db.prc_MMSIFQueryBalance(username);

                // 打印原始返回数据
                logger.info("数据库返回原始数据: {}", balanceMap);

                // 检查返回数据是否为空
                if (balanceMap == null || balanceMap.isEmpty()) {
                    logger.warn("数据库返回空数据 - username: {}", username);
                    return AjaxResult.error(404, "No balance data found");
                }

                // 检查必要的字段是否存在
                if (!balanceMap.containsKey("Balance") || !balanceMap.containsKey("Credit")) {
                    logger.error("返回数据缺少必要字段 - balanceMap: {}", balanceMap);
                    return AjaxResult.error(500, "Invalid data structure from database");
                }

                long balanceRaw = Long.parseLong(balanceMap.get("Balance").toString());
                long creditRaw = Long.parseLong(balanceMap.get("Credit").toString());

                double balance = balanceRaw / 10000.0;
                double credit = creditRaw / 10000.0;

                Map<String, Object> data = new HashMap<>();
                data.put("Balance", String.format("%.4f", balance));
                data.put("Credit", String.format("%.4f", credit));

                logger.info("查询成功 - username: {}, balance: {}, credit: {}", username, balance, credit);
                return AjaxResult.success("Query successful", data);

            } finally {
                if (db != null) {
                    db.Disconnect();
                    db.Destroy();
                }
            }

        } catch (NumberFormatException e) {
            logger.error("数据格式转换错误 - username: {}", request.getUsername(), e);
            return AjaxResult.error(500, "Data format error: " + e.getMessage());
        } catch (Exception e) {
            logger.error("/open/api/sms/balance exception - username: {}", request.getUsername(), e);
            return AjaxResult.error(500, "Query failed: " + e.getMessage());
        }
    }

    // ====================== 内部查询余额接口 ======================
    @PostMapping("/internal/balance")
    public AjaxResult internalQueryBalance(@RequestBody InternalBalanceRequest request) {
        try {
            String username = request.getUsername();
            String password = request.getPassword();

            if (StringUtils.isAnyBlank(username, password)) {
                return AjaxResult.error(400, "用户名和密码不能为空");
            }

            String correctPassword = getCustomerPassword(username);
            if (StringUtils.isEmpty(correctPassword)) {
                return AjaxResult.error(401, "用户不存在");
            }
            if (!correctPassword.equals(password)) {
                return AjaxResult.error(401, "密码错误");
            }

            NCacheDBLink db = null;
            try {
                db = NCacheDBLink.getNCacheDBLink();
                db.InitEnv();
                db.ConnectToCacheDB();
                Map<String, Object> balanceMap = db.prc_MMSIFQueryBalance(username);

                long balanceRaw = Long.parseLong(balanceMap.get("Balance").toString());
                long creditRaw = Long.parseLong(balanceMap.get("Credit").toString());

                double balance = balanceRaw / 10000.0;
                double credit = creditRaw / 10000.0;

                Map<String, Object> data = new HashMap<>();
                data.put("username", username);
                data.put("balance", String.format("%.4f", balance));
                data.put("credit", String.format("%.4f", credit));
                data.put("queryTime", new SimpleDateFormat("yyyy‑MM‑dd HH:mm:ss").format(new Date()));
                return AjaxResult.success("查询成功", data);
            } finally {
                if (db != null) {
                    db.Disconnect();
                    db.Destroy();
                }
            }

        } catch (Exception e) {
            logger.error("/open/api/sms/internal/balance exception", e);
            return AjaxResult.error(500, "查询失败：" + e.getMessage());
        }
    }

    // ====================== 根据TaskId查询短信详情 ======================
    @PostMapping("/task/detail")
    public AjaxResult getTaskDetail(@RequestBody TaskDetailRequest request) {
        logger.info("========== 查询任务详情请求开始 ==========");

        try {
            String username = request.getUsername();
            String timestamp = request.getTimestamp();
            String sign = request.getSign();
            String taskId = request.getTaskId();
            Integer pageNum = request.getPageNum();

            final int PAGE_SIZE = 10;

            logger.info("请求参数 - username: {}, taskId: {}, pageNum: {}",
                    username, taskId, pageNum);

            if (StringUtils.isAnyBlank(username, timestamp, sign, taskId)) {
                logger.warn("参数缺失 - username: {}, taskId: {}", username, taskId);
                return AjaxResult.error(400, "Required parameters are missing");
            }

            if (pageNum == null || pageNum < 1) {
                pageNum = 1;
            }

            // 时间戳校验
            long reqTimestamp;
            try {
                reqTimestamp = Long.parseLong(timestamp);
            } catch (NumberFormatException e) {
                logger.warn("时间戳格式错误 - username: {}, timestamp: {}", username, timestamp);
                return AjaxResult.error(400, "Invalid timestamp format");
            }
            long reqSec = (timestamp.length() == 13) ? reqTimestamp / 1000 : reqTimestamp;
            long nowSec = System.currentTimeMillis() / 1000;
            if (Math.abs(nowSec - reqSec) > 300) {
                logger.warn("请求过期 - username: {}", username);
                return AjaxResult.error(401, "Request expired");
            }

            String password = getCustomerPassword(username);
            if (StringUtils.isEmpty(password)) {
                logger.warn("用户不存在 - username: {}", username);
                return AjaxResult.error(401, "Account does not exist or password error");
            }

            AjaxResult check = CustomerApiSignUtil.verifySign(username, timestamp, sign, password);
            if (check != null) {
                return check;
            }
            logger.info("签名验证成功 - username: {}", username);

            // 越权校验
            String taskOwnerAccount = smsQueueMapper.getTaskOwnerByTaskId(taskId);
            if (StringUtils.isEmpty(taskOwnerAccount)) {
                logger.warn("任务不存在 - username: {}, taskId: {}", username, taskId);
                return AjaxResult.error(404, "Task not found");
            }
            if (!username.equals(taskOwnerAccount)) {
                logger.warn("无权限访问 - username: {}, taskId: {}", username, taskId);
                return AjaxResult.error(403, "No permission to access this task");
            }

            // ==================== 统计全部数据的送达状态 ====================
            Map<String, Object> stats = smsQueueMapper.selectDrStatsByTaskId(taskId);

            long successCount = 0;
            long failCount = 0;

            if (stats != null) {
                Object succObj = stats.get("drSuccCount");
                Object failObj = stats.get("drFailCount");
                successCount = succObj != null ? ((Number) succObj).longValue() : 0;
                failCount = failObj != null ? ((Number) failObj).longValue() : 0;
            }

            logger.info("全部数据统计 - 成功: {}, 失败: {}", successCount, failCount);

            // ==================== 分页查询明细 ====================
            Map<String, Object> pageResult = smsQueueService.getQueueTaskDetail(taskId, pageNum, PAGE_SIZE, null);

            if (pageResult == null) {
                pageResult = new HashMap<>();
                pageResult.put("records", Collections.emptyList());
                pageResult.put("total", 0L);
            }

            // ==================== 构建响应 ====================
            Long total = (Long) pageResult.getOrDefault("total", 0L);
            int maxPage = total <= 0 ? 1 : (int) Math.ceil((double) total / PAGE_SIZE);

            if (pageNum > maxPage) {
                int originalPageNum = pageNum;
                pageNum = maxPage;
                logger.warn("页码超出范围，自动修正 - 原始页码: {}, 修正为: {}", originalPageNum, pageNum);
                pageResult = smsQueueService.getQueueTaskDetail(taskId, pageNum, PAGE_SIZE, null);
                if (pageResult == null) {
                    pageResult = new HashMap<>();
                    pageResult.put("records", Collections.emptyList());
                    pageResult.put("total", total);
                }
            }

            // 查询任务汇总统计
            Map<String, Object> taskSummary = smsQueueMapper.getTaskSummary(taskId);
            if (taskSummary == null) {
                taskSummary = new HashMap<>();
            }

            // 汇总信息（添加统计字段）
            Map<String, Object> summaryWithStats = new HashMap<>();
            if (taskSummary != null) {
                summaryWithStats.putAll(taskSummary);
            }
            summaryWithStats.put("DRSuccCount", successCount);  // 送达成功数（全部数据）
            summaryWithStats.put("DRFailCount", failCount);     // 送达失败数（全部数据）

            Map<String, Object> respData = new HashMap<>();
            respData.put("summary", summaryWithStats);
            respData.put("records", pageResult.getOrDefault("records", Collections.emptyList()));
            respData.put("total", pageResult.getOrDefault("total", 0L));
            respData.put("pageNum", pageResult.getOrDefault("pageNum", pageNum));
            respData.put("pageSize", PAGE_SIZE);
            respData.put("pageCount", maxPage);

            logger.info("查询成功 - username: {}, taskId: {}, 总记录数: {}, 当前页: {}, 总页数: {}",
                    username, taskId, total, pageNum, maxPage);
            logger.info("========== 查询任务详情请求结束 ==========");

            return AjaxResult.success("Query successful", respData);

        } catch (Exception e) {
            logger.error("查询任务详情异常 - username: {}, taskId: {}",
                    request.getUsername(), request.getTaskId(), e);
            return AjaxResult.error(500, "Query failed: " + e.getMessage());
        }
    }

    @PostMapping("/sendBatch")
    public AjaxResult sendBatchSms(
            @RequestBody BatchSmsSendRequest request,
            HttpServletRequest httpRequest) {

        try {
            String username = request.getUsername();
            String timestamp = request.getTimestamp();
            String sign = request.getSign();

            if (StringUtils.isAnyBlank(username, timestamp, sign)) {
                return AjaxResult.error(400, "Required parameters are missing");
            }

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

            String password = getCustomerPassword(username);
            if (StringUtils.isEmpty(password)) {
                return AjaxResult.error(401, "Account does not exist or password error");
            }

            AjaxResult check = CustomerApiSignUtil.verifySign(username, timestamp, sign, password);
            if (check.get("Code") == null || (Integer) check.get("Code") != 0) {
                return AjaxResult.error(401, "Authentication failed");
            }

            if (StringUtils.isEmpty(request.getPreTaskId())) {
                return AjaxResult.error(400, "preTaskId cannot be empty");
            }
            if (StringUtils.isEmpty(request.getContent())) {
                return AjaxResult.error(400, "Content cannot be empty");
            }

            // 接收对象，再getTaskId
            SmsSendResponse sendResp = smsSendService.doSendBatch(username, request, httpRequest);
            String taskId = sendResp.getTaskId();

            Map<String, Object> data = new HashMap<>();
            data.put("MsgId", taskId);
            return AjaxResult.success("Batch send submitted", data);

        } catch (Exception e) {
            e.printStackTrace();
            return AjaxResult.error(500, "Send failed: " + e.getMessage());
        }
    }

    /**
     * 批量上传 20MB
     */
    @PostMapping("/uploadCalleeBatch")
    public AjaxResult uploadCalleeBatch(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "numbers", required = false) String numbers,
            @RequestParam String username,
            @RequestParam String timestamp,
            @RequestParam String sign,
            HttpServletRequest httpRequest
    ) {
        try {
            // 1.公共验签逻辑，复制sendBatch
            if (StringUtils.isAnyBlank(username, timestamp, sign)) {
                return AjaxResult.error(400, "Required parameters are missing");
            }
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
            String password = getCustomerPassword(username);
            if (StringUtils.isEmpty(password)) {
                return AjaxResult.error(401, "Account does not exist or password error");
            }
            AjaxResult check = CustomerApiSignUtil.verifySign(username, timestamp, sign, password);
            if (check.get("Code") == null || (Integer) check.get("Code") != 0) {
                return AjaxResult.error(401, "Authentication failed");
            }

            // 业务解析
            List<String> phoneList = new ArrayList<>();
            int totalLineCount = 0;
            if (file != null && !file.isEmpty()) {
                SmsUploadService.ParseResult result = smsUploadService.parsePhoneBatch(file);
                phoneList = result.getPhoneList();
                totalLineCount = result.getTotalLineCount();
            } else if (StringUtils.isNotEmpty(numbers)) {
                phoneList = Arrays.stream(numbers.split("[,\\n]"))
                        .map(String::trim)
                        .filter(s -> s.matches("^\\d+$") && s.length() >= 8 && s.length() <= 21)
                        .distinct()
                        .collect(Collectors.toList());
                totalLineCount = phoneList.size();
            }
            if (phoneList.isEmpty()) {
                return AjaxResult.error(400, "No valid numbers detected");
            }

            String preTaskId = IdGenerator.generatePreTaskId();
            smsUploadService.savePreTask(username, preTaskId, phoneList);

            Map<String, Object> data = new HashMap<>();
            data.put("PreTaskId", preTaskId);
            data.put("CalleeCount", phoneList.size());
            data.put("TotalLineCount", totalLineCount);
            // 禁止返回完整号码列表！！
            // data.put("Callee", phoneList);

            return AjaxResult.success("upload success", data);
        } catch (Exception e) {
            e.printStackTrace();
            return AjaxResult.error(500, "upload failed:" + e.getMessage());
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
        private String taskId;
        private Integer pageNum = 1;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(String timestamp) {
            this.timestamp = timestamp;
        }

        public String getSign() {
            return sign;
        }

        public void setSign(String sign) {
            this.sign = sign;
        }

        public String getTaskId() {
            return taskId;
        }

        public void setTaskId(String taskId) {
            this.taskId = taskId;
        }

        public Integer getPageNum() {
            return pageNum;
        }

        public void setPageNum(Integer pageNum) {
            this.pageNum = pageNum;
        }
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