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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SubmitRecordService {

    private static final Logger logger = LoggerFactory.getLogger(SubmitRecordService.class);
    @Resource
    private SubmitRecordMapper submitRecordMapper;
    private String SubAccount() { return TokenService.getUsername(); }
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

    public SubmitRecordWithStatsDTO getSubmitRecordWithStats(
            Date startTime, Date endTime,
            String callee, String mcc, String mnc,
            String innerReportState, String innerReportError,
            String destAccountStatus,
            Integer pageNum, Integer pageSize
    ) {
        DynamicDataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_ECCOM);
        try {
            String subAccount = SubAccount();
            pageSize = Math.min(pageSize, 50);
            if (startTime == null) startTime = new Date(System.currentTimeMillis() - 12 * 3600 * 1000);
            if (endTime == null) endTime = new Date();
            String startTimeStr = DATETIME_FORMAT_DB.format(startTime);
            String endTimeStr = DATETIME_FORMAT_DB.format(endTime);

            callee = (callee == null) ? "" : callee.trim();
            mcc = (mcc == null) ? "" : mcc.trim();
            mnc = (mnc == null) ? "" : mnc.trim();
            innerReportState = (innerReportState == null) ? "" : innerReportState.trim();
            destAccountStatus = (destAccountStatus == null) ? "" : destAccountStatus.trim();

            List<String> suffixList = getDateSuffixList(startTime, endTime);
            List<SubmitRecordDTO> allSubmitList = new ArrayList<>();
            Map<String, Map<String, Object>> feeMap = new HashMap<>();
            long submitSuccess = 0, submitFail = 0;

            for (String suffix : suffixList) {
                List<SubmitRecordDTO> subList = submitRecordMapper.querySubmitBill(
                        suffix, subAccount, startTimeStr, endTimeStr, callee, mcc, mnc, destAccountStatus
                );
                allSubmitList.addAll(subList);
                Map<String, Object> countMap = submitRecordMapper.countSubmitStats(
                        suffix, subAccount, startTimeStr, endTimeStr, callee, mcc, mnc, destAccountStatus
                );
                submitSuccess += ((Number) countMap.get("successCount")).longValue();
                submitFail += ((Number) countMap.get("failCount")).longValue();
                List<Map<String, Object>> feeList = submitRecordMapper.queryFeeBill(
                        suffix, subAccount, startTimeStr, endTimeStr, innerReportState
                );
                for (Map<String, Object> fee : feeList) {
                    String recvMsgId = (String) fee.get("RecvMsgId");
                    if (recvMsgId != null) feeMap.put(recvMsgId, fee);
                }
            }

            List<SubmitRecordDTO> mergedList = new ArrayList<>();
            Map<String, Integer> stateCount = new HashMap<>();
            stateCount.put("DELIVRD", 0); stateCount.put("UNDELIV", 0); stateCount.put("UNKNOWN", 0);

            for (SubmitRecordDTO sub : allSubmitList) {
                Map<String, Object> fee = feeMap.get(sub.getSubmitMsgId());
                if (!innerReportState.isEmpty()) {
                    if (fee == null) continue;
                    String realState = String.valueOf(fee.get("InnerReportState"));
                    if (!innerReportState.equals(realState)) continue;
                }
                if (fee != null) {
                    String state = (String) fee.get("InnerReportState");
                    sub.setInnerReportState(state);
                    sub.setRecvDRTime((String) fee.get("RecvDRTime"));
                    if ("DELIVRD".equals(state)) stateCount.put("DELIVRD", stateCount.get("DELIVRD") + 1);
                    else if ("UNDELIV".equals(state)) stateCount.put("UNDELIV", stateCount.get("UNDELIV") + 1);
                    else if ("UNKNOWN".equals(state)) stateCount.put("UNKNOWN", stateCount.get("UNKNOWN") + 1);
                } else {
                    sub.setInnerReportState("已提交");
                    sub.setRecvDRTime(null);
                    if (!innerReportState.isEmpty()) continue;
                }
                try {
                    String content = SmsContentParserUtil.parseAndEncryptShortMessage(sub.getOriginalShortMessage(), sub.getDcs());
                    sub.setShortMessage(content == null ? "" : content.replaceAll("\\n|\\r", " "));
                } catch (Exception e) {
                    sub.setShortMessage("");
                }
                String chargeFee = sub.getChargeFee();
                if (chargeFee == null || "0".equals(chargeFee)) {
                    sub.setActualChargeFee("0");
                } else {
                    try {
                        BigDecimal feeVal = new BigDecimal(chargeFee);
                        BigDecimal actual = feeVal.divide(new BigDecimal(10000), 4, RoundingMode.HALF_UP);
                        sub.setActualChargeFee(actual.stripTrailingZeros().toPlainString());
                    } catch (Exception e) {
                        sub.setActualChargeFee("0");
                    }
                }
                mergedList.add(sub);
            }

            int total = mergedList.size();
            int startIndex = (pageNum - 1) * pageSize;
            int endIndex = Math.min(startIndex + pageSize, total);
            List<SubmitRecordDTO> pageRecords = startIndex >= total ? new ArrayList<>() : mergedList.subList(startIndex, endIndex);
            long submitTotal = submitSuccess + submitFail;
            long deliverSuccess = stateCount.get("DELIVRD");
            long deliverFail = stateCount.get("UNDELIV") + stateCount.get("UNKNOWN");
            String deliverRate = "0.00%";
            if (submitSuccess > 0) {
                double rate = Math.min((double) deliverSuccess / submitSuccess, 1.0);
                deliverRate = PERCENT_FORMAT.format(rate);
            }

            SubmitRecordWithStatsDTO dto = new SubmitRecordWithStatsDTO();
            dto.setRecords(pageRecords);
            dto.setSubmitTotal(submitTotal);
            dto.setSubmitFailCount(submitFail);
            dto.setSubmitSuccessRate("100.00%");
            dto.setDeliverSuccessCount(deliverSuccess);
            dto.setDeliverFailCount(deliverFail);
            dto.setDeliverSuccessRate(deliverRate);
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
            String innerReportState, String innerReportError,
            String destAccountStatus
    ) {
        DynamicDataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_ECCOM);
        try {
            String subAccount = SubAccount();
            String startTimeStr = DATETIME_FORMAT_DB.format(startTime);
            String endTimeStr = DATETIME_FORMAT_DB.format(endTime);
            callee = (callee == null) ? "" : callee.trim();
            mcc = (mcc == null) ? "" : mcc.trim();
            mnc = (mnc == null) ? "" : mnc.trim();
            innerReportState = (innerReportState == null) ? "" : innerReportState.trim();
            destAccountStatus = (destAccountStatus == null) ? "" : destAccountStatus.trim();

            final String finalInner = innerReportState;
            List<String> suffixList = getDateSuffixList(startTime, endTime);
            List<SubmitRecordDTO> allSubmitList = new ArrayList<>();
            Map<String, Map<String, Object>> feeMap = new HashMap<>();

            for (String suffix : suffixList) {
                List<SubmitRecordDTO> subList = submitRecordMapper.querySubmitBill(
                        suffix, subAccount, startTimeStr, endTimeStr, callee, mcc, mnc, destAccountStatus
                );
                allSubmitList.addAll(subList);
                List<Map<String, Object>> feeList = submitRecordMapper.queryFeeBill(
                        suffix, subAccount, startTimeStr, endTimeStr, innerReportState
                );
                for (Map<String, Object> fee : feeList) {
                    String recvMsgId = (String) fee.get("RecvMsgId");
                    if (recvMsgId != null) feeMap.put(recvMsgId, fee);
                }
            }

            return allSubmitList.stream()
                    .filter(sub -> {
                        if (finalInner.isEmpty()) return true;
                        Map<String, Object> fee = feeMap.get(sub.getSubmitMsgId());
                        return fee != null && finalInner.equals(String.valueOf(fee.get("InnerReportState")));
                    })
                    .map(sub -> {
                        SubmitRecordExportVO vo = new SubmitRecordExportVO();
                        Map<String, Object> fee = feeMap.get(sub.getSubmitMsgId());
                        vo.setSourAccount(sub.getSourAccount());
                        vo.setSubmitMsgId(sub.getSubmitMsgId());
                        vo.setSubmitMsgTime(sub.getSubmitMsgTime());
                        vo.setCaller(sub.getCaller());
                        vo.setCallee(sub.getCallee());
                        vo.setSmLength(sub.getSmLength());
                        String chargeFee = sub.getChargeFee();
                        if (chargeFee == null || "0".equals(chargeFee)) {
                            vo.setActualChargeFee("0");
                        } else {
                            try {
                                BigDecimal feeVal = new BigDecimal(chargeFee);
                                BigDecimal actual = feeVal.divide(new BigDecimal(10000), 4, RoundingMode.HALF_UP);
                                vo.setActualChargeFee(actual.stripTrailingZeros().toPlainString());
                            } catch (Exception e) {
                                vo.setActualChargeFee("0");
                            }
                        }
                        try {
                            String content = SmsContentParserUtil.parseShortMessageOnly(sub.getOriginalShortMessage(), sub.getDcs());
                            vo.setShortMessage(content == null ? "" : content.replaceAll("\\n|\\r", " "));
                        } catch (Exception e) {
                            vo.setShortMessage("");
                        }
                        vo.setMcc(sub.getMcc());
                        vo.setMnc(sub.getMnc());
                        if (fee != null) {
                            vo.setInnerReportState((String) fee.get("InnerReportState"));
                            vo.setRecvDRTime((String) fee.get("RecvDRTime"));
                        } else {
                            vo.setInnerReportState("已提交");
                            vo.setRecvDRTime(null);
                        }
                        return vo;
                    }).collect(Collectors.toList());
        } finally {
            DynamicDataSourceInterceptor.clearCurrentDataSource();
        }
    }
}