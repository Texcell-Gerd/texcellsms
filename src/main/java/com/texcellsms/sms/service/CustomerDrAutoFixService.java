package com.texcellsms.sms.service;

import com.texcellsms.sms.mapper.webportal.CustomerDrAutoFixConfigMapper;
import com.texcellsms.sms.mapper.webportal.QueueMonitorLogMapper;
import com.texcellsms.sms.mapper.webportal.SmsQueueMapper;
import com.texcellsms.sms.model.entity.CustomerDrAutoFixConfig;
import com.texcellsms.sms.model.entity.QueueMonitorLog;
import com.texcellsms.sms.util.WeChatRobotUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class CustomerDrAutoFixService {

    private static final Logger logger = LoggerFactory.getLogger(CustomerDrAutoFixService.class);

    @Autowired
    private CustomerDrAutoFixConfigMapper configMapper;

    @Autowired
    private SmsQueueMapper smsQueueMapper;

    @Autowired
    private QueueMonitorLogMapper queueMonitorLogMapper;

    @Autowired
    private WeChatRobotUtil weChatRobotUtil;

    /**
     * 保存配置
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveConfig(CustomerDrAutoFixConfig config, String operator) {
        if (config.getId() == null || config.getId() == 0) {
            config.setCreatedBy(operator);
            configMapper.insert(config);
        } else {
            config.setUpdatedBy(operator);
            configMapper.update(config);
        }
        logger.info("保存客户DR自动修复配置: operator={}, subAccount={}", operator, config.getSubAccount());
    }

    /**
     * 删除配置
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteConfig(Long id, String operator) {
        configMapper.deleteById(id);
        logger.info("删除客户DR自动修复配置: operator={}, id={}", operator, id);
    }

    /**
     * 获取所有启用配置的客户列表
     */
    public List<String> getEnabledSubAccounts() {
        return configMapper.selectAllEnabledSubAccounts();
    }

    /**
     * 分页查询配置列表
     */
    public Map<String, Object> getConfigList(String subAccount, Integer enabled, int pageNum, int pageSize) {
        int offset = (pageNum - 1) * pageSize;
        List<CustomerDrAutoFixConfig> list = configMapper.selectPage(offset, pageSize, subAccount, enabled);
        long total = configMapper.count(subAccount, enabled);

        Map<String, Object> result = new HashMap<>();
        result.put("records", list);
        result.put("total", total);
        result.put("pageNum", pageNum);
        result.put("pageSize", pageSize);
        result.put("totalPages", (total + pageSize - 1) / pageSize);
        return result;
    }

    /**
     * 定时任务：执行客户DR自动修复
     * 每分钟执行一次，检查所有启用的配置
     */
    @Scheduled(cron = "0 */1 * * * ?")
    public void executeCustomerDrAutoFix() {
        logger.info("开始执行客户DR自动修复定时任务");

        try {
            // 1. 获取所有启用的配置
            List<CustomerDrAutoFixConfig> configs = configMapper.selectAllEnabled();
            if (configs.isEmpty()) {
                logger.info("没有启用的客户DR自动修复配置");
                return;
            }

            int totalFixed = 0;
            int totalFailed = 0;

            for (CustomerDrAutoFixConfig config : configs) {
                try {
                    int fixed = processCustomerAutoFix(config);
                    totalFixed += fixed;
                } catch (Exception e) {
                    logger.error("处理客户 {} 自动修复失败: {}", config.getSubAccount(), e.getMessage(), e);
                    totalFailed++;
                }
            }

            logger.info("客户DR自动修复完成: 修复 {} 条DR记录, 失败 {} 个客户", totalFixed, totalFailed);
        } catch (Exception e) {
            logger.error("客户DR自动修复定时任务执行失败", e);
        }
    }

    /**
     * 处理单个客户的自动修复
     */
    private int processCustomerAutoFix(CustomerDrAutoFixConfig config) {
        String subAccount = config.getSubAccount();
        int timeoutMinutes = config.getTimeoutMinutes();
        int minTotalCount = config.getMinTotalCount();
        int maxPendingCount = config.getMaxPendingCount();

        logger.info("处理客户 {} 的自动修复，超时时间: {} 分钟", subAccount, timeoutMinutes);

        List<Map<String, Object>> tasks = smsQueueMapper.findTimeoutTasksForCustomer(subAccount, timeoutMinutes);

        if (tasks.isEmpty()) {
            logger.info("客户 {} 没有超时任务", subAccount);
            return 0;
        }

        logger.info("客户 {} 找到 {} 个超时任务", subAccount, tasks.size());

        int fixedCount = 0;
        List<String> fixedTaskIds = new ArrayList<>();

        for (Map<String, Object> task : tasks) {
            String taskId = (String) task.get("taskId");
            if (taskId == null || taskId.trim().isEmpty()) {
                logger.warn("任务ID为空，跳过");
                continue;
            }

            Integer totalCount = ((Number) task.getOrDefault("totalCount", 0)).intValue();
            Integer detailPendingCount = ((Number) task.getOrDefault("detailPendingCount", 0)).intValue();

            if (minTotalCount > 0 && totalCount < minTotalCount) {
                logger.debug("任务 {} 总发送数 {} 小于最小阈值 {}, 跳过", taskId, totalCount, minTotalCount);
                continue;
            }

            if (maxPendingCount > 0 && detailPendingCount > maxPendingCount) {
                logger.debug("任务 {} 未回执数 {} 大于最大阈值 {}, 跳过", taskId, detailPendingCount, maxPendingCount);
                continue;
            }

            try {
                int updated = smsQueueMapper.executeFixSql(taskId);
                if (updated > 0) {
                    List<String> singleTaskId = new ArrayList<>();
                    singleTaskId.add(taskId);
                    try {
                        smsQueueMapper.batchUpdateTaskStats(singleTaskId);
                    } catch (Exception e) {
                        logger.warn("更新任务 {} 主表统计失败: {}", taskId, e.getMessage());
                    }

                    fixedCount += updated;
                    fixedTaskIds.add(taskId);

                    updateOrInsertMonitorLog(taskId, subAccount, "PROCESSED", task);

                    logger.info("客户 {} 自动修复任务 {} 成功，更新 {} 条DR记录", subAccount, taskId, updated);
                }
            } catch (Exception e) {
                logger.error("客户 {} 自动修复任务 {} 失败: {}", subAccount, taskId, e.getMessage());
            }
        }

        // 4. 发送通知
        if (!fixedTaskIds.isEmpty()) {
            Integer notifyAfterFix = config.getNotifyAfterFix();
            if (notifyAfterFix != null && notifyAfterFix == 1) {
                try {
                    sendAutoFixNotification(config, fixedTaskIds, fixedCount);
                } catch (Exception e) {
                    logger.warn("发送自动修复通知失败: {}", e.getMessage());
                }
            }
        }

        return fixedCount;
    }

    public CustomerDrAutoFixConfig getConfigById(Long id) {
        return configMapper.selectById(id);
    }

    /**
     * 发送自动修复通知
     */
    private void sendAutoFixNotification(CustomerDrAutoFixConfig config, List<String> taskIds, int fixedCount) {
        try {
            if (!weChatRobotUtil.isEnabled()) {
                logger.info("企业微信机器人未启用，跳过通知");
                return;
            }

            // ✅ 空判断
            if (config == null || taskIds == null || taskIds.isEmpty()) {
                logger.warn("通知参数为空，跳过发送");
                return;
            }

            String subAccount = config.getSubAccount();
            String msg = String.format(
                    "## 🤖 客户DR自动修复通知\n\n" +
                            "| 项目 | 数值 |\n" +
                            "|------|------|\n" +
                            "| 客户账号 | **%s** |\n" +
                            "| 超时时间 | **%d 分钟** |\n" +
                            "| 修复任务数 | **%d** 个 |\n" +
                            "| 修复记录数 | **%d** 条 |\n\n" +
                            "**修复任务ID：**\n`%s`\n\n" +
                            "> ⏰ 系统已自动修复超时未回执的任务",
                    subAccount,
                    config.getTimeoutMinutes(),
                    taskIds.size(),
                    fixedCount,
                    String.join("\n`", taskIds)
            );

            // ✅ webhook 空判断
            String webhook = config.getNotifyWebhook() != null && !config.getNotifyWebhook().isEmpty()
                    ? config.getNotifyWebhook()
                    : null;

            boolean success = weChatRobotUtil.sendMarkdownMessage(msg, webhook);
            if (success) {
                logger.info("自动修复通知发送成功: 客户={}, 任务数={}", subAccount, taskIds.size());
            } else {
                logger.warn("自动修复通知发送失败: 客户={}", subAccount);
            }
        } catch (Exception e) {
            logger.error("发送自动修复通知失败", e);
        }
    }

    /**
     * 更新或插入监控日志状态
     */
    private void updateOrInsertMonitorLog(String taskId, String subAccount, String status,
                                          Map<String, Object> taskData) {
        try {
            QueueMonitorLog existing = queueMonitorLogMapper.selectLatestByTaskId(taskId);
            if (existing != null) {
                queueMonitorLogMapper.updateStatus(taskId, status, "系统自动", LocalDateTime.now());
                logger.debug("更新监控日志状态成功: taskId={}, status={}", taskId, status);
            } else {
                QueueMonitorLog log = new QueueMonitorLog();
                log.setTaskId(taskId);
                log.setSubAccount(subAccount);
                log.setStatus(status);
                log.setProcessedBy("系统自动");
                log.setProcessedTime(LocalDateTime.now());
                log.setAlertSent(0);

                // ✅ 从任务数据中获取真实值
                log.setTaskName((String) taskData.getOrDefault("taskName", ""));
                log.setTotalCount(((Number) taskData.getOrDefault("totalCount", 0)).intValue());
                log.setSubmitSuccCount(((Number) taskData.getOrDefault("submitSuccCount", 0)).intValue());
                log.setDrSuccCount(((Number) taskData.getOrDefault("drSuccCount", 0)).intValue());
                log.setDrFailCount(((Number) taskData.getOrDefault("drFailCount", 0)).intValue());
                log.setPendingCount(((Number) taskData.getOrDefault("pendingCount", 0)).intValue());
                log.setDetailPendingCount(((Number) taskData.getOrDefault("detailPendingCount", 0)).intValue());

                // 计算已过时间
                Integer elapsedMinutes = ((Number) taskData.getOrDefault("elapsedMinutes", 0)).intValue();
                log.setElapsedMinutes(elapsedMinutes);
                log.setAlertLevel(elapsedMinutes >= 60 ? "CRITICAL" : "WARNING");

                // 任务时间
                String taskTimeStr = (String) taskData.get("taskTimeStr");
                if (taskTimeStr != null && !taskTimeStr.trim().isEmpty()) {
                    try {
                        String timeStr = taskTimeStr.trim();
                        if (timeStr.contains(".")) {
                            timeStr = timeStr.substring(0, timeStr.indexOf("."));
                        }
                        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                        log.setTaskTime(LocalDateTime.parse(timeStr, formatter));
                    } catch (Exception e) {
                        log.setTaskTime(LocalDateTime.now().withNano(0));
                    }
                } else {
                    log.setTaskTime(LocalDateTime.now().withNano(0));
                }

                queueMonitorLogMapper.insert(log);
                logger.debug("插入监控日志记录成功: taskId={}, status={}", taskId, status);
            }
        } catch (Exception e) {
            logger.warn("更新/插入监控日志状态失败: taskId={}, error={}", taskId, e.getMessage());
        }
    }
}