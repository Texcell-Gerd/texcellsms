package com.texcellsms.sms.service;

import com.texcellsms.sms.config.DynamicDataSourceConfig;
import com.texcellsms.sms.interceptor.DynamicDataSourceInterceptor;
import com.texcellsms.sms.mapper.eccom.SubmitRecordMapper;
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

    private String SubAccount() {
        return TokenService.getUsername();
    }

    private static final SimpleDateFormat DATETIME_FORMAT_DB = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyyMMdd");

    private static final NumberFormat PERCENT_FORMAT;
    static {
        PERCENT_FORMAT = NumberFormat.getPercentInstance(Locale.US);
        PERCENT_FORMAT.setMaximumFractionDigits(2);
        PERCENT_FORMAT.setGroupingUsed(false);
    }

    private List<String> getDateSuffixList(Date start, Date end) {
        List<String> suffixList = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        cal.setTime(start);
        while (!cal.getTime().after(end)) {
            suffixList.add(DATE_FORMAT.format(cal.getTime()));
            cal.add(Calendar.DATE, 1);
        }
        return suffixList;
    }

    private List<SubmitRecordDTO> querySubmitRecordsCommon(
            Date startTime, Date endTime,
            String callee, String mcc, String mnc,
            String innerReportState, String innerReportError,
            Integer pageNum, Integer pageSize
    ) {
        DynamicDataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_ECCOM);
        try {
            if (startTime == null) {
                startTime = new Date(System.currentTimeMillis() - 12 * 3600 * 1000);
            }
            if (endTime == null) {
                endTime = new Date();
            }

            String subAccount = SubAccount();
            String startTimeStr = DATETIME_FORMAT_DB.format(startTime);
            String endTimeStr = DATETIME_FORMAT_DB.format(endTime);

            String state = (innerReportState == null) ? "" : innerReportState.trim();
            String error = (innerReportError == null) ? "" : innerReportError.trim();
            String calleeNo = (callee == null) ? "" : callee.trim();
            String mccCode = (mcc == null) ? "" : mcc.trim();
            String mncCode = (mnc == null) ? "" : mnc.trim();

            List<String> tableSuffixList = getDateSuffixList(startTime, endTime);
            List<SubmitRecordDTO> allRecords = new ArrayList<>();

            for (String suffix : tableSuffixList) {
                List<SubmitRecordDTO> dayData = submitRecordMapper.querySubmitRecordsWithJoin(
                        suffix, subAccount, startTimeStr, endTimeStr,
                        calleeNo, mccCode, mncCode, state, error,
                        9999, 0
                );
                allRecords.addAll(dayData);
            }

            for (SubmitRecordDTO record : allRecords) {
                if (record.getFbInnerReportState() != null && !record.getFbInnerReportState().isEmpty()) {
                    record.setInnerReportState(record.getFbInnerReportState());
                } else {
                    record.setInnerReportState("处理中");
                    record.setRecvDRTime(null);
                }

                String content = SmsContentParserUtil.parseAndEncryptShortMessage(
                        record.getOriginalShortMessage(), record.getDcs()
                );
                record.setShortMessage(content == null ? "" : content.replaceAll("\\n|\\r", " "));

                record.setFbInnerReportState(null);
                record.setInnerReportError(null);
                record.setChargeFee(null);
            }

            int startIndex = (pageNum - 1) * pageSize;
            if (startIndex >= allRecords.size()) {
                return new ArrayList<>();
            }
            int endIndex = Math.min(startIndex + pageSize, allRecords.size());
            return allRecords.subList(startIndex, endIndex);

        } finally {
            DynamicDataSourceInterceptor.clearCurrentDataSource();
        }
    }

    // ====================== 【已修复】传入所有筛选条件 ======================
    private Long countTotal(Date startTime, Date endTime, String subAccount,
                            String callee, String mcc, String mnc,
                            String innerReportState, String innerReportError) {
        List<String> suffixList = getDateSuffixList(startTime, endTime);
        long total = 0L;
        String sTime = DATETIME_FORMAT_DB.format(startTime);
        String eTime = DATETIME_FORMAT_DB.format(endTime);

        for (String suffix : suffixList) {
            Long count = submitRecordMapper.countSubmitTotal(
                    suffix, subAccount, sTime, eTime,
                    callee, mcc, mnc, innerReportState, innerReportError
            );
            if (count != null) total += count;
        }
        return total;
    }

    // ====================== 【已修复】传入所有筛选条件 ======================
    private Map<String, Long> countDeliver(Date startTime, Date endTime, String subAccount,
                                           String callee, String mcc, String mnc,
                                           String innerReportState, String innerReportError) {
        List<String> suffixList = getDateSuffixList(startTime, endTime);
        Map<String, Long> result = new HashMap<>();
        result.put("success", 0L);
        result.put("fail", 0L);

        String sTime = DATETIME_FORMAT_DB.format(startTime);
        String eTime = DATETIME_FORMAT_DB.format(endTime);

        for (String suffix : suffixList) {
            List<Map<String, Object>> stats = submitRecordMapper.countDeliverStats(
                    suffix, subAccount, sTime, eTime,
                    callee, mcc, mnc, innerReportState, innerReportError
            );
            for (Map<String, Object> map : stats) {
                String state = String.valueOf(map.get("InnerReportState"));
                long count = Long.parseLong(String.valueOf(map.get("count")));

                if ("DELIVRD".equalsIgnoreCase(state)) {
                    result.put("success", result.get("success") + count);
                } else {
                    result.put("fail", result.get("fail") + count);
                }
            }
        }
        return result;
    }

    public SubmitRecordWithStatsDTO getSubmitRecordWithStats(
            Date startTime, Date endTime,
            String callee, String mcc, String mnc,
            String innerReportState, String innerReportError,
            Integer pageNum, Integer pageSize
    ) {
        DynamicDataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_ECCOM);
        try {
            String subAccount = SubAccount();
            pageSize = Math.min(pageSize, 50);

            String state = (innerReportState == null) ? "" : innerReportState.trim();
            String error = (innerReportError == null) ? "" : innerReportError.trim();
            String calleeNo = (callee == null) ? "" : callee.trim();
            String mccCode = (mcc == null) ? "" : mcc.trim();
            String mncCode = (mnc == null) ? "" : mnc.trim();

            List<SubmitRecordDTO> records = querySubmitRecordsCommon(
                    startTime, endTime, callee, mcc, mnc,
                    innerReportState, innerReportError,
                    pageNum, pageSize
            );

            // ====================== 【已修复】统计方法带上条件 ======================
            Long total = countTotal(startTime, endTime, subAccount,
                    calleeNo, mccCode, mncCode, state, error);
            Map<String, Long> deliver = countDeliver(startTime, endTime, subAccount,
                    calleeNo, mccCode, mncCode, state, error);

            Long success = deliver.get("success");
            Long fail = deliver.get("fail");

            String rate = "0.00%";
            if (total > 0) {
                rate = PERCENT_FORMAT.format((double) success / total);
            }

            SubmitRecordWithStatsDTO dto = new SubmitRecordWithStatsDTO();
            dto.setRecords(records);
            dto.setSubmitTotal(total);
            dto.setSubmitFailCount(fail);
            dto.setDeliverSuccessCount(success);
            dto.setDeliverFailCount(fail);
            dto.setDeliverSuccessRate(rate);
            dto.setSubmitSuccessRate("100.00%");
            dto.setPageCount(total <= 0 ? 0 : (int) Math.ceil((double) total / pageSize));
            dto.setCurrentPage(pageNum);
            dto.setPageSize(pageSize);

            return dto;
        } finally {
            DynamicDataSourceInterceptor.clearCurrentDataSource();
        }
    }

    public List<SubmitRecordExportVO> queryExportDataForEasyExcel(
            Date startTime, Date endTime,
            String callee, String mcc, String mnc,
            String innerReportState, String innerReportError
    ) {
        DynamicDataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_ECCOM);
        try {
            String subAccount = SubAccount();
            String startTimeStr = DATETIME_FORMAT_DB.format(startTime);
            String endTimeStr = DATETIME_FORMAT_DB.format(endTime);

            String state = (innerReportState == null) ? "" : innerReportState.trim();
            String error = (innerReportError == null) ? "" : innerReportError.trim();
            String calleeNo = (callee == null) ? "" : callee.trim();
            String mccCode = (mcc == null) ? "" : mcc.trim();
            String mncCode = (mnc == null) ? "" : mnc.trim();

            List<String> suffixList = getDateSuffixList(startTime, endTime);
            List<SubmitRecordDTO> allData = new ArrayList<>();

            for (String suffix : suffixList) {
                List<SubmitRecordDTO> dayData = submitRecordMapper.querySubmitRecordsForExportWithJoin(
                        suffix, subAccount, startTimeStr, endTimeStr,
                        calleeNo, mccCode, mncCode, state, error
                );
                allData.addAll(dayData);
            }

            logger.info("导出总数据量: {}", allData.size());

            return allData.stream().map(item -> {
                SubmitRecordExportVO vo = new SubmitRecordExportVO();
                vo.setSourAccount(item.getSourAccount());
                vo.setSubmitMsgId(item.getSubmitMsgId());
                vo.setSubmitMsgTime(item.getSubmitMsgTime());
                vo.setCaller(item.getCaller());
                vo.setCallee(item.getCallee());
                vo.setSmLength(item.getSmLength());
                vo.setActualChargeFee(item.getActualChargeFee());

                String content = SmsContentParserUtil.parseShortMessageOnly(item.getOriginalShortMessage(), item.getDcs());
                vo.setShortMessage(content == null ? "" : content.replaceAll("\\n|\\r", " "));

                vo.setMcc(item.getMcc());
                vo.setMnc(item.getMnc());
                vo.setInnerReportState(item.getFbInnerReportState() == null ? "处理中" : item.getFbInnerReportState());
                vo.setRecvDRTime(item.getRecvDRTime());
                return vo;
            }).collect(Collectors.toList());

        } finally {
            DynamicDataSourceInterceptor.clearCurrentDataSource();
        }
    }
}