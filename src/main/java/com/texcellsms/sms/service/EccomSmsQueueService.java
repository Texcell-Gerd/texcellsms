package com.texcellsms.sms.service;

import com.texcellsms.sms.config.DynamicDataSourceConfig;
import com.texcellsms.sms.interceptor.DynamicDataSourceInterceptor;
import com.texcellsms.sms.mapper.eccom.SmsQueueMapper;
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

@Service
public class EccomSmsQueueService {

    private static final Logger logger = LoggerFactory.getLogger(EccomSmsQueueService.class);

    @Resource
    private SmsQueueMapper smsQueueMapper;

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyyMMdd");
    private static final SimpleDateFormat DATETIME_FORMAT_DB = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
    private static final SimpleDateFormat DATETIME_FORMAT_PARSE = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public SmsQueueDTO getSmsQueueList(String loginUserAccount, Integer page, Integer pageSize) {
        DynamicDataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_ECCOM);
        try {
            if (page == null || page < 1) page = 1;
            if (pageSize == null || pageSize < 1) pageSize = 10;
            pageSize = Math.min(pageSize, 50);
            Integer offset = (page - 1) * pageSize;

            Date endTime = new Date();
            Date startTime = new Date(endTime.getTime() - 3L * 24 * 3600 * 1000);
            String startTimeStr = DATETIME_FORMAT_DB.format(startTime);
            String endTimeStr = DATETIME_FORMAT_DB.format(endTime);

            String tableSuffix = DATE_FORMAT.format(startTime);
            logger.info("查询队列列表: 用户={}, 时间范围={}~{}, 分页={}/{}",
                    loginUserAccount, startTimeStr, endTimeStr, page, pageSize);

            long sqlStart = System.currentTimeMillis();
            List<SmsQueueItemDTO> queueItems = smsQueueMapper.queryQueueListBySecond(
                    tableSuffix, loginUserAccount, startTimeStr, endTimeStr, offset, pageSize
            );
            long sqlEnd = System.currentTimeMillis();
            logger.info("队列列表查询耗时: {}ms, 返回{}条", sqlEnd - sqlStart, queueItems.size());

            Long total = smsQueueMapper.countQueueTotal(tableSuffix, loginUserAccount, startTimeStr, endTimeStr);
            total = total == null ? 0L : total;
            Integer pageCount = total > 0 ? (int) Math.ceil((double) total / pageSize) : 0;

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

    public SmsQueueDetailDTO getSmsQueueDetail(String loginUserAccount, String targetTime, String callee) {
        DynamicDataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_ECCOM);
        try {
            if (targetTime == null || targetTime.isEmpty()) {
                throw new IllegalArgumentException("队列时间不能为空");
            }

            Date targetDate = DATETIME_FORMAT_DB.parse(targetTime);
            String tableSuffix = DATE_FORMAT.format(targetDate);
            logger.info("查询队列明细: 用户={}, 目标时间={}, 表后缀={}, 搜索号码={}",
                    loginUserAccount, targetTime, tableSuffix, callee);

            long sqlStart = System.currentTimeMillis();
            List<SmsQueueDetailItemDTO> detailItems = new ArrayList<>();
            try {
                detailItems = smsQueueMapper.queryQueueDetailByTime(
                        tableSuffix, loginUserAccount, targetTime, callee
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

            SmsQueueDetailDTO result = new SmsQueueDetailDTO();
            result.setSubmitMsgTime(targetTime);
            result.setDetails(detailItems);

            return result;
        } catch (Exception e) {
            logger.error("查询队列明细失败", e);
            SmsQueueDetailDTO errorResult = new SmsQueueDetailDTO();
            errorResult.setSubmitMsgTime(targetTime);
            errorResult.setDetails(new ArrayList<>());
            errorResult.setERROR("查询失败: " + e.getMessage());
            return errorResult;
        } finally {
            DynamicDataSourceInterceptor.clearCurrentDataSource();
        }
    }

    public SmsQueueDTO getSmsQueueListForTest(String loginUserAccount, String startTimeStr, String endTimeStr, Integer page, Integer pageSize) {
        DynamicDataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_ECCOM);
        try {
            if (page == null || page < 1) page = 1;
            if (pageSize == null || pageSize < 1) pageSize = 10;
            pageSize = Math.min(pageSize, 50);
            Integer offset = (page - 1) * pageSize;

            Date startTime = DATETIME_FORMAT_PARSE.parse(startTimeStr);
            Date endTime = DATETIME_FORMAT_PARSE.parse(endTimeStr);
            String dbStartTime = DATETIME_FORMAT_DB.format(startTime);
            String dbEndTime = DATETIME_FORMAT_DB.format(endTime);
            String tableSuffix = DATE_FORMAT.format(startTime);

            List<SmsQueueItemDTO> queueItems = smsQueueMapper.queryQueueListBySecond(
                    tableSuffix, loginUserAccount, dbStartTime, dbEndTime, offset, pageSize
            );
            Long total = smsQueueMapper.countQueueTotal(tableSuffix, loginUserAccount, dbStartTime, dbEndTime);
            Integer pageCount = total > 0 ? (int) Math.ceil((double) total / pageSize) : 0;

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