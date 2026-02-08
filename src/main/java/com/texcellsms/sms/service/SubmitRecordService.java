package com.texcellsms.sms.service;

import com.texcellsms.sms.config.DynamicDataSourceConfig;
import com.texcellsms.sms.interceptor.DynamicDataSourceInterceptor;
import com.texcellsms.sms.mapper.prepay.SubmitRecordMapper;
import com.texcellsms.sms.model.DTO.SubmitRecordDTO;
import com.texcellsms.sms.model.DTO.SubmitRecordWithStatsDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.*;

@Service
public class SubmitRecordService {

    private static final Logger logger = LoggerFactory.getLogger(SubmitRecordService.class);

    @Resource
    private SubmitRecordMapper submitRecordMapper;

    // 分表后缀格式化器
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyyMMdd");
    // 数据库需要的日期格式（/分隔）
    private static final SimpleDateFormat DATETIME_FORMAT_DB = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

    // 移除金额格式化相关的NumberFormat定义
    private static final NumberFormat PERCENT_FORMAT;
    static {
        // 仅保留百分比格式化
        PERCENT_FORMAT = NumberFormat.getPercentInstance(Locale.US);
        PERCENT_FORMAT.setMaximumFractionDigits(2);
        PERCENT_FORMAT.setGroupingUsed(false);
    }

    /**
     * 原始查询方法，移除金额计算逻辑
     */
    private List<SubmitRecordDTO> querySubmitRecordsCommon(
            String sourAccount, Date startTime, Date endTime,
            String callee, String mcc, String mnc,
            String innerReportState, String innerReportError,
            Integer pageNum, Integer pageSize
    ) {
        long startTimeMs = System.currentTimeMillis();

        DynamicDataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_PREPAY);

        try {
            long now = System.currentTimeMillis();
            if (startTime == null) {
                startTime = new Date(now - 2 * 3600 * 1000);
            }
            if (endTime == null) {
                endTime = new Date(now);
            }

            String startTimeStr = DATETIME_FORMAT_DB.format(startTime);
            String endTimeStr = DATETIME_FORMAT_DB.format(endTime);
            String tableSuffix = DATE_FORMAT.format(startTime);

            pageSize = Math.min(pageSize, 50);
            Integer offset = (pageNum - 1) * pageSize;

            if (offset > 1000) {
                logger.warn("OFFSET过大: {}，查询可能很慢，建议添加索引或使用其他方案", offset);
                return Collections.emptyList();
            }

            logger.info("开始查询: account={}, start={}, end={}, page={}, size={}, offset={}",
                    sourAccount, startTimeStr, endTimeStr, pageNum, pageSize, offset);

            long sqlStart = System.currentTimeMillis();
            List<SubmitRecordDTO> records = submitRecordMapper.querySubmitRecordsWithJoin(
                    tableSuffix, sourAccount, startTimeStr, endTimeStr,
                    callee, mcc, mnc, innerReportState, innerReportError,
                    pageSize, offset
            );
            long sqlEnd = System.currentTimeMillis();

            logger.info("SQL查询耗时: {}ms, 返回记录数: {}", sqlEnd - sqlStart, records.size());

            // 分批处理记录，移除金额计算逻辑
            if (!records.isEmpty()) {
                int batchSize = Math.min(100, records.size());
                for (int i = 0; i < records.size(); i += batchSize) {
                    int end = Math.min(i + batchSize, records.size());
                    List<SubmitRecordDTO> batch = records.subList(i, end);
                    processBatchRecords(batch);
                }
            }

            long totalTime = System.currentTimeMillis() - startTimeMs;
            logger.info("查询总耗时: {}ms", totalTime);

            if (totalTime > 5000) {
                logger.warn("查询耗时过长: {}ms, 请检查数据库性能", totalTime);
            }

            return records;
        } finally {
            DynamicDataSourceInterceptor.clearCurrentDataSource();
        }
    }

    /**
     * 分批处理记录 - 仅保留必要逻辑，移除金额计算
     */
    private void processBatchRecords(List<SubmitRecordDTO> batch) {
        for (SubmitRecordDTO record : batch) {
            // 1. 统一回执状态
            if (record.getFbInnerReportState() != null && !record.getFbInnerReportState().isEmpty()) {
                record.setInnerReportState(record.getFbInnerReportState());
            } else {
                record.setRecvDRTime(null);
                record.setInnerReportState("已提交");
            }

            // 2. 处理短信内容换行符
            if (record.getShortMessage() != null) {
                record.setShortMessage(record.getShortMessage().replaceAll("\\n", " "));
            }

            // 3. 清空临时字段（移除金额格式化逻辑）
            record.setFbInnerReportState(null);
            record.setInnerReportError(null);
            record.setChargeFee(null); // 原始ChargeFee字段清空
        }
    }

    // 以下原有方法（getSubmitRecordsByLoginUser、getSubmitRecordsForDebug、getSubmitRecordWithStats、getSubmitRecordsFast）
    // 完全保留，无需修改

    // 移除formatActualFee方法（不再使用）

    public List<SubmitRecordDTO> getSubmitRecordsByLoginUser(
            String loginUserAccount, Date startTime, Date endTime,
            String callee, String mcc, String mnc,
            String innerReportState, String innerReportError,
            Integer pageNum, Integer pageSize
    ) {
        return querySubmitRecordsCommon(
                loginUserAccount, startTime, endTime,
                callee, mcc, mnc,
                innerReportState, innerReportError,
                pageNum, pageSize
        );
    }

    public List<SubmitRecordDTO> getSubmitRecordsForDebug(
            String sourAccount, Date startTime, Date endTime,
            String callee, String mcc, String mnc,
            String innerReportState, String innerReportError,
            Integer pageNum, Integer pageSize
    ) {
        return querySubmitRecordsCommon(
                sourAccount, startTime, endTime,
                callee, mcc, mnc,
                innerReportState, innerReportError,
                pageNum, pageSize
        );
    }

    public SubmitRecordWithStatsDTO getSubmitRecordWithStats(
            String sourAccount, Date startTime, Date endTime,
            String callee, String mcc, String mnc,
            String innerReportState, String innerReportError,
            Integer pageNum, Integer pageSize
    ) {
        DynamicDataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_PREPAY);
        try {
            long now = System.currentTimeMillis();
            if (startTime == null) startTime = new Date(now - 2 * 3600 * 1000);
            if (endTime == null) endTime = new Date(now);

            String startTimeStr = DATETIME_FORMAT_DB.format(startTime);
            String endTimeStr = DATETIME_FORMAT_DB.format(endTime);
            String tableSuffix = DATE_FORMAT.format(startTime);
            pageSize = Math.min(pageSize, 50);

            List<SubmitRecordDTO> records = querySubmitRecordsCommon(
                    sourAccount, startTime, endTime,
                    callee, mcc, mnc,
                    innerReportState, innerReportError,
                    pageNum, pageSize
            );

            Long submitTotal = submitRecordMapper.countSubmitTotal(
                    tableSuffix, sourAccount, startTimeStr, endTimeStr
            );
            if (submitTotal == null) submitTotal = 0L;

            List<Map<String, Object>> deliverStats = submitRecordMapper.countDeliverStats(
                    tableSuffix, sourAccount, startTimeStr, endTimeStr
            );
            Long deliverSuccess = 0L;
            Long deliverFail = 0L;
            for (Map<String, Object> stat : deliverStats) {
                String state = (String) stat.get("InnerReportState");
                Long count = ((Number) stat.get("count")).longValue();
                if ("DELIVRD".equals(state)) {
                    deliverSuccess = count;
                } else {
                    deliverFail += count;
                }
            }

            String deliverSuccessRate = "0.00%";
            if (submitTotal > 0) {
                double rate = (double) deliverSuccess / submitTotal;
                deliverSuccessRate = PERCENT_FORMAT.format(rate);
            }

            Integer pageCount = submitTotal > 0 ? (int) Math.ceil((double) submitTotal / pageSize) : 0;

            SubmitRecordWithStatsDTO result = new SubmitRecordWithStatsDTO();
            result.setRecords(records);
            result.setSubmitTotal(submitTotal);
            result.setSubmitFailCount(0L);
            result.setSubmitSuccessRate("100.00%");
            result.setDeliverSuccessCount(deliverSuccess);
            result.setDeliverFailCount(deliverFail);
            result.setDeliverSuccessRate(deliverSuccessRate);
            result.setPageCount(pageCount);
            result.setCurrentPage(pageNum);
            result.setPageSize(pageSize);

            return result;
        } finally {
            DynamicDataSourceInterceptor.clearCurrentDataSource();
        }
    }

    public List<SubmitRecordDTO> getSubmitRecordsFast(
            String sourAccount, Date startTime, Date endTime,
            Integer pageNum, Integer pageSize
    ) {
        if (pageNum > 10) {
            logger.warn("页码过大: {}, 限制为前10页", pageNum);
            return Collections.emptyList();
        }

        return getSubmitRecordsForDebug(
                sourAccount, startTime, endTime,
                null, null, null, null, null,
                pageNum, pageSize
        );
    }
}