package com.texcellsms.sms.service;

import com.texcellsms.sms.config.DynamicDataSourceConfig;
import com.texcellsms.sms.interceptor.DynamicDataSourceInterceptor;
import com.texcellsms.sms.mapper.prepay.SmsQueueMapper;
import com.texcellsms.sms.model.DTO.SmsQueueDTO;
import com.texcellsms.sms.model.DTO.SmsQueueDetailDTO;
import com.texcellsms.sms.model.DTO.SmsQueueDetailItemDTO;
import com.texcellsms.sms.model.DTO.SmsQueueItemDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 短信队列Service（支持测试/线上环境）
 */
@Service
public class SmsQueueService {

    private static final Logger logger = LoggerFactory.getLogger(SmsQueueService.class);

    @Resource
    private SmsQueueMapper smsQueueMapper;

    // 分表后缀格式化器
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyyMMdd");
    // 数据库时间格式
    private static final SimpleDateFormat DATETIME_FORMAT_DB = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
    // 时间解析格式（兼容前端传入）
    private static final SimpleDateFormat DATETIME_FORMAT_PARSE = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /**
     * 查询近三天队列列表（支持分页）
     */
    public SmsQueueDTO getSmsQueueList(String loginUserAccount, Integer page, Integer pageSize) {
        DynamicDataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_PREPAY);
        try {
            // 1. 参数校验与默认值
            if (page == null || page < 1) page = 1;
            if (pageSize == null || pageSize < 1) pageSize = 10;
            pageSize = Math.min(pageSize, 50); // 最大50条
            Integer offset = (page - 1) * pageSize;

            // 2. 计算近三天时间范围（当前时间-3天 到 当前时间）
            Date endTime = new Date();
            Date startTime = new Date(endTime.getTime() - 3L * 24 * 3600 * 1000);
            String startTimeStr = DATETIME_FORMAT_DB.format(startTime);
            String endTimeStr = DATETIME_FORMAT_DB.format(endTime);

            // 3. 处理分表查询
            String tableSuffix = DATE_FORMAT.format(startTime);
            logger.info("查询队列列表: 用户={}, 时间范围={}~{}, 分页={}/{}",
                    loginUserAccount, startTimeStr, endTimeStr, page, pageSize);

            // 4. 查询队列列表
            long sqlStart = System.currentTimeMillis();
            List<SmsQueueItemDTO> queueItems = smsQueueMapper.queryQueueListBySecond(
                    tableSuffix, loginUserAccount, startTimeStr, endTimeStr, offset, pageSize
            );
            long sqlEnd = System.currentTimeMillis();
            logger.info("队列列表查询耗时: {}ms, 返回{}条", sqlEnd - sqlStart, queueItems.size());

            // 5. 统计总条数和总页数
            Long total = smsQueueMapper.countQueueTotal(tableSuffix, loginUserAccount, startTimeStr, endTimeStr);
            total = total == null ? 0L : total;
            Integer pageCount = total > 0 ? (int) Math.ceil((double) total / pageSize) : 0;

            // 6. 组装返回结果
            SmsQueueDTO result = new SmsQueueDTO();
            result.setPage(page);
            result.setPageSize(pageSize);
            result.setTotal(total);
            result.setPageCount(pageCount);
            result.setItems(queueItems);

            return result;
        } finally {
            DynamicDataSourceInterceptor.clearCurrentDataSource();
        }
    }

    /**
     * 查询指定队列的明细数据
     */
    public SmsQueueDetailDTO getSmsQueueDetail(String loginUserAccount, String targetTime, String callee) {
        DynamicDataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_PREPAY);
        try {
            // 1. 参数校验
            if (targetTime == null || targetTime.isEmpty()) {
                throw new IllegalArgumentException("队列时间不能为空");
            }

            // 2. 解析目标时间，获取表后缀
            Date targetDate = DATETIME_FORMAT_DB.parse(targetTime);
            String tableSuffix = DATE_FORMAT.format(targetDate);
            logger.info("查询队列明细: 用户={}, 目标时间={}, 表后缀={}, 搜索号码={}",
                    loginUserAccount, targetTime, tableSuffix, callee);

            // 3. 查询明细数据（设置超时控制）
            long sqlStart = System.currentTimeMillis();
            List<SmsQueueDetailItemDTO> detailItems = new ArrayList<>();
            try {
                // 限制查询超时时间（10秒）
                detailItems = smsQueueMapper.queryQueueDetailByTime(
                        tableSuffix, loginUserAccount, targetTime, callee // 传入搜索参数
                );
            } catch (Exception e) {
                if (e.getMessage().contains("timeout") || e.getMessage().contains("closed")) {
                    logger.warn("查询队列明细超时，返回空列表", e);
                    detailItems = new ArrayList<>();
                } else {
                    throw e;
                }
            }
            long sqlEnd = System.currentTimeMillis();
            logger.info("队列明细查询耗时: {}ms, 返回{}条", sqlEnd - sqlStart, detailItems.size());

            // 4. 组装返回结果
            SmsQueueDetailDTO result = new SmsQueueDetailDTO();
            result.setSubmitMsgTime(targetTime);
            result.setDetails(detailItems);

            return result;
        } catch (Exception e) {
            logger.error("查询队列明细失败", e);
            // 异常返回空结果
            SmsQueueDetailDTO errorResult = new SmsQueueDetailDTO();
            errorResult.setSubmitMsgTime(targetTime);
            errorResult.setDetails(new ArrayList<>());
            errorResult.setERROR("查询失败: " + e.getMessage());
            return errorResult;
        } finally {
            DynamicDataSourceInterceptor.clearCurrentDataSource();
        }
    }

    /**
     * 测试环境查询队列列表（指定时间范围）
     */
    public SmsQueueDTO getSmsQueueListForTest(String loginUserAccount, String startTimeStr, String endTimeStr, Integer page, Integer pageSize) {
        DynamicDataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_PREPAY);
        try {
            // 1. 参数校验
            if (page == null || page < 1) page = 1;
            if (pageSize == null || pageSize < 1) pageSize = 10;
            pageSize = Math.min(pageSize, 50);
            Integer offset = (page - 1) * pageSize;

            // 2. 解析时间参数（兼容yyyy-MM-dd HH:mm:ss）
            Date startTime = DATETIME_FORMAT_PARSE.parse(startTimeStr);
            Date endTime = DATETIME_FORMAT_PARSE.parse(endTimeStr);
            String dbStartTime = DATETIME_FORMAT_DB.format(startTime);
            String dbEndTime = DATETIME_FORMAT_DB.format(endTime);
            String tableSuffix = DATE_FORMAT.format(startTime);

            // 3. 查询列表和统计
            List<SmsQueueItemDTO> queueItems = smsQueueMapper.queryQueueListBySecond(
                    tableSuffix, loginUserAccount, dbStartTime, dbEndTime, offset, pageSize
            );
            Long total = smsQueueMapper.countQueueTotal(tableSuffix, loginUserAccount, dbStartTime, dbEndTime);
            Integer pageCount = total > 0 ? (int) Math.ceil((double) total / pageSize) : 0;

            // 4. 组装结果
            SmsQueueDTO result = new SmsQueueDTO();
            result.setPage(page);
            result.setPageSize(pageSize);
            result.setTotal(total);
            result.setPageCount(pageCount);
            result.setItems(queueItems);

            return result;
        } catch (Exception e) {
            logger.error("测试环境查询队列失败", e);
            throw new RuntimeException("测试查询失败: " + e.getMessage());
        } finally {
            DynamicDataSourceInterceptor.clearCurrentDataSource();
        }
    }
}