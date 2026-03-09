package com.texcellsms.sms.service;

import com.texcellsms.sms.config.DynamicDataSourceConfig;
import com.texcellsms.sms.interceptor.DynamicDataSourceInterceptor;
import com.texcellsms.sms.mapper.prepay.SubmitRecordMapper;
import com.texcellsms.sms.model.DTO.SubmitRecordDTO;
import com.texcellsms.sms.model.DTO.SubmitRecordWithStatsDTO;
import com.texcellsms.sms.model.vo.SubmitRecordExportVO;
import com.texcellsms.sms.util.SmsContentParserUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SubmitRecordService {

    private static final Logger logger = LoggerFactory.getLogger(SubmitRecordService.class);

    @Resource
    private SubmitRecordMapper submitRecordMapper;

    //获取当前登录用户名
    private String SubAccount() {
        return TokenService.getUsername();
    };

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
     * 发送记录查询
     */
    private List<SubmitRecordDTO> querySubmitRecordsCommon(
            Date startTime, Date endTime,
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
            String subAccount = SubAccount();
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
                    subAccount, startTimeStr, endTimeStr, pageNum, pageSize, offset);

            long sqlStart = System.currentTimeMillis();
            List<SubmitRecordDTO> records = submitRecordMapper.querySubmitRecordsWithJoin(
                    tableSuffix, subAccount, startTimeStr, endTimeStr,
                    callee, mcc, mnc, innerReportState, innerReportError,
                    pageSize, offset
            );
            long sqlEnd = System.currentTimeMillis();

            logger.info("SQL查询耗时: {}ms, 返回记录数: {}", sqlEnd - sqlStart, records.size());

            // ========== 数据逻辑处理 ==========
            if (!records.isEmpty()) {
                int batchSize = Math.min(100, records.size());
                for (int i = 0; i < records.size(); i += batchSize) {
                    int end = Math.min(i + batchSize, records.size());
                    List<SubmitRecordDTO> batch = records.subList(i, end);

                    // 批量处理每条记录
                    for (SubmitRecordDTO record : batch) {
                        // 1. 统一回执状态（原有逻辑）
                        if (record.getFbInnerReportState() != null && !record.getFbInnerReportState().isEmpty()) {
                            record.setInnerReportState(record.getFbInnerReportState());
                        } else {
                            record.setRecvDRTime(null);
                            record.setInnerReportState("已提交");
                        }

                        // 2. 解析+BASE64加密短信内容
                        String encryptedMsg = SmsContentParserUtil.parseAndEncryptShortMessage(
                                record.getOriginalShortMessage(),
                                record.getDcs()
                        );
                        record.setShortMessage(encryptedMsg);

                        // 3. 处理短信内容换行符
                        if (record.getShortMessage() != null) {
                            record.setShortMessage(record.getShortMessage().replaceAll("\\n", " "));
                        }

                        // 4. 清空临时字段
                        record.setFbInnerReportState(null);
                        record.setInnerReportError(null);
                        record.setChargeFee(null);
                    }
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

    public List<SubmitRecordDTO> getSubmitRecordsByLoginUser(
            Date startTime, Date endTime,
            String callee, String mcc, String mnc,
            String innerReportState, String innerReportError,
            Integer pageNum, Integer pageSize
    ) {
        return querySubmitRecordsCommon(
                startTime, endTime,
                callee, mcc, mnc,
                innerReportState, innerReportError,
                pageNum, pageSize
        );
    }

    public SubmitRecordWithStatsDTO getSubmitRecordWithStats(
            Date startTime, Date endTime,
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
            String subAccount = SubAccount();
            pageSize = Math.min(pageSize, 50);

            List<SubmitRecordDTO> records = querySubmitRecordsCommon(
                    startTime, endTime,
                    callee, mcc, mnc,
                    innerReportState, innerReportError,
                    pageNum, pageSize
            );

            Long submitTotal = submitRecordMapper.countSubmitTotal(
                    tableSuffix, subAccount, startTimeStr, endTimeStr
            );
            if (submitTotal == null) submitTotal = 0L;

            List<Map<String, Object>> deliverStats = submitRecordMapper.countDeliverStats(
                    tableSuffix, subAccount, startTimeStr, endTimeStr
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

    /**
     * 查询导出数据（EasyExcel专用，流式查询避免OOM）
     */
    public List<SubmitRecordExportVO> queryExportDataForEasyExcel(
            Date startTime, Date endTime,
            String callee, String mcc, String mnc,
            String innerReportState, String innerReportError
    ) {
        DynamicDataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_PREPAY);
        try {
            String startTimeStr = DATETIME_FORMAT_DB.format(startTime);
            String endTimeStr = DATETIME_FORMAT_DB.format(endTime);
            String tableSuffix = DATE_FORMAT.format(startTime);
            String subAccount = SubAccount();

            // 调用导出专用查询
            List<SubmitRecordDTO> dtoList = submitRecordMapper.querySubmitRecordsForExportWithJoin(
                    tableSuffix, subAccount, startTimeStr, endTimeStr,
                    callee, mcc, mnc, innerReportState, innerReportError
            );

            logger.info("导出查询返回记录数: {}", dtoList.size());

            // DTO 转 VO（仅解析短信内容，不加密）
            return dtoList.stream().map(dto -> {
                SubmitRecordExportVO vo = new SubmitRecordExportVO();
                vo.setSourAccount(dto.getSourAccount() == null ? "" : dto.getSourAccount());
                vo.setSubmitMsgId(dto.getSubmitMsgId() == null ? "" : dto.getSubmitMsgId());
                vo.setSubmitMsgTime(dto.getSubmitMsgTime() == null ? "" : dto.getSubmitMsgTime());
                vo.setCaller(dto.getCaller() == null ? "" : dto.getCaller());
                vo.setCallee(dto.getCallee() == null ? "" : dto.getCallee());
                vo.setSmLength(dto.getSmLength() == null ? "" : dto.getSmLength());
                vo.setActualChargeFee(dto.getActualChargeFee() == null ? "0.0000" : dto.getActualChargeFee());

                // 解析短信内容
                String parsedMsg = SmsContentParserUtil.parseShortMessageOnly(
                        dto.getOriginalShortMessage(),
                        dto.getDcs()
                );
                // 处理换行符，优化导出格式
                vo.setShortMessage(parsedMsg == null ? "" : parsedMsg.replaceAll("\\n", " "));

                vo.setMcc(dto.getMcc() == null ? "" : dto.getMcc());
                vo.setMnc(dto.getMnc() == null ? "" : dto.getMnc());

                // 统一回执状态
                String reportState = dto.getFbInnerReportState() != null && !dto.getFbInnerReportState().isEmpty()
                        ? dto.getFbInnerReportState()
                        : "已提交";
                vo.setInnerReportState(reportState);

                vo.setRecvDRTime(dto.getRecvDRTime() == null ? "" : dto.getRecvDRTime());
                return vo;
            }).collect(Collectors.toList());
        } finally {
            DynamicDataSourceInterceptor.clearCurrentDataSource();
        }
    }
}