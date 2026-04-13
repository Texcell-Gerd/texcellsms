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
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SubmitRecordService {

    private static final Logger logger = LoggerFactory.getLogger(SubmitRecordService.class);

    @Resource
    private SubmitRecordMapper submitRecordMapper;

    /**
     * 获取当前登录用户
     */
    private String SubAccount() {
        return TokenService.getUsername();
    }

    /**
     * 百分比格式（线程安全）
     */
    private static final NumberFormat PERCENT_FORMAT;
    static {
        PERCENT_FORMAT = NumberFormat.getPercentInstance(Locale.US);
        PERCENT_FORMAT.setMaximumFractionDigits(2);
        PERCENT_FORMAT.setGroupingUsed(false);
    }

    /**
     * 获取日期表后缀列表（按天分表）
     */
    private List<String> getDateSuffixList(Date start, Date end) {
        List<String> suffixList = new ArrayList<>();
        if (start == null || end == null) {
            return suffixList;
        }

        Calendar cal = Calendar.getInstance();
        cal.setTime(start);
        Date endDate = end;

        // 关键修复：保证包含结束日期当天
        Calendar endCal = Calendar.getInstance();
        endCal.setTime(end);
        endCal.add(Calendar.DATE, 1); // 结束日期+1天，确保循环包含当天

        while (cal.before(endCal)) {
            suffixList.add(String.format("%tY%<tm%<td", cal.getTime()));
            cal.add(Calendar.DATE, 1);
        }
        return suffixList;
    }

    /**
     * 分页查询短信发送记录 + 统计（最新数据优先）
     */
    public SubmitRecordWithStatsDTO getSubmitRecordWithStats(
            Date startTime, Date endTime,
            String callee, String mcc, String mnc,
            String innerReportState, String innerReportError,
            String destAccountStatus,
            Integer pageNum, Integer pageSize
    ) {
        DynamicDataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_ECCOM);
        final int FIX_PAGE_SIZE = 10;
        pageNum = (pageNum == null || pageNum < 1) ? 1 : pageNum;

        String subAccount = SubAccount();
        String startTimeStr = String.format("%tY/%<tm/%<td %<tH:%<tM:%<tS", startTime);
        String endTimeStr = String.format("%tY/%<tm/%<td %<tH:%<tM:%<tS", endTime);

        callee = Objects.toString(callee, "").trim();
        mcc = Objects.toString(mcc, "").trim();
        mnc = Objects.toString(mnc, "").trim();
        final String finalInnerReportState = Objects.toString(innerReportState, "").trim();
        destAccountStatus = Objects.toString(destAccountStatus, "").trim();

        List<String> suffixList = getDateSuffixList(startTime, endTime);

        try {
            // 1. 批量查询 msgId + 时间 + 分表后缀
            Map<String, String> msgIdToTableSuffix = new HashMap<>();
            Map<String, String> msgIdToSubmitTime = new HashMap<>();
            List<Map<String, Object>> allIdWithTime = new ArrayList<>();

            for (String suffix : suffixList) {
                List<Map<String, Object>> idWithTimeList = submitRecordMapper.listSubmitMsgIdsWithTime(
                        suffix, subAccount, startTimeStr, endTimeStr, callee, mcc, mnc, destAccountStatus
                );
                allIdWithTime.addAll(idWithTimeList);
                for (Map<String, Object> map : idWithTimeList) {
                    String msgId = (String) map.get("SubmitMsgId");
                    String submitTime = (String) map.get("SubmitMsgTime");
                    msgIdToTableSuffix.put(msgId, suffix);
                    msgIdToSubmitTime.put(msgId, submitTime);
                }
            }

            // 2. 查询回执状态
            Map<String, Map<String, Object>> feeMap = new HashMap<>();
            for (String suffix : suffixList) {
                List<Map<String, Object>> feeList = submitRecordMapper.queryFeeBill(
                        suffix, subAccount, startTimeStr, endTimeStr, finalInnerReportState, callee, mcc, mnc
                );
                for (Map<String, Object> fee : feeList) {
                    String recvMsgId = (String) fee.get("RecvMsgId");
                    if (recvMsgId != null && msgIdToTableSuffix.containsKey(recvMsgId)) {
                        feeMap.put(recvMsgId, fee);
                    }
                }
            }

            // 3. 过滤有效ID（全局倒序）
            List<String> validIds = new ArrayList<>();
            for (Map<String, Object> map : allIdWithTime) {
                String msgId = (String) map.get("SubmitMsgId");
                if (!finalInnerReportState.isEmpty() && !feeMap.containsKey(msgId)) {
                    continue;
                }
                validIds.add(msgId);
            }

            validIds.sort((id1, id2) -> {
                String t1 = msgIdToSubmitTime.get(id1);
                String t2 = msgIdToSubmitTime.get(id2);
                if (t1 == null) return 1;
                if (t2 == null) return -1;
                return t2.compareTo(t1);
            });

            // 4. 统计
            long total = validIds.size();
            long deliverSuccess = 0, deliverFail = 0;
            for (String id : validIds) {
                Map<String, Object> f = feeMap.get(id);
                if (f == null) continue;
                String state = String.valueOf(f.get("InnerReportState"));
                if ("DELIVRD".equals(state)) {
                    deliverSuccess++;
                } else if ("UNDELIV".equals(state) || "UNKNOWN".equals(state)) {
                    deliverFail++;
                }
            }

            long submitSuccess = total;
            long submitFail = 0;
            if (finalInnerReportState.isEmpty()) {
                if ("success".equals(destAccountStatus)) {
                    submitSuccess = total;
                    submitFail = 0;
                } else if ("fail".equals(destAccountStatus)) {
                    submitFail = total;
                    submitSuccess = 0;
                }
            }

            // 5. 分页查询
            int skip = (pageNum - 1) * FIX_PAGE_SIZE;
            List<String> pageIds = validIds.stream().skip(skip).limit(FIX_PAGE_SIZE).collect(Collectors.toList());
            List<SubmitRecordDTO> pageRecords = new ArrayList<>();

            if (!pageIds.isEmpty()) {
                Map<String, List<String>> tableToIds = new HashMap<>();
                for (String id : pageIds) {
                    String suffix = msgIdToTableSuffix.get(id);
                    if (suffix != null) {
                        tableToIds.computeIfAbsent(suffix, k -> new ArrayList<>()).add(id);
                    }
                }
                for (Map.Entry<String, List<String>> entry : tableToIds.entrySet()) {
                    pageRecords.addAll(submitRecordMapper.listByIds(entry.getKey(), entry.getValue()));
                }
            }

            pageRecords.sort(Comparator.comparing(SubmitRecordDTO::getSubmitMsgTime, Comparator.nullsLast(Comparator.reverseOrder())));

            // 6. 封装返回
            List<SubmitRecordDTO> merged = new ArrayList<>();
            for (SubmitRecordDTO sub : pageRecords) {
                Map<String, Object> f = feeMap.get(sub.getSubmitMsgId());
                if (f != null) {
                    sub.setInnerReportState((String) f.get("InnerReportState"));
                    sub.setRecvDRTime((String) f.get("RecvDRTime"));
                } else {
                    sub.setInnerReportState("已提交");
                    sub.setRecvDRTime(null);
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
                merged.add(sub);
            }

            String submitSuccessRate = total > 0 ? PERCENT_FORMAT.format((double) submitSuccess / total) : "0.00%";
            String deliverRate = submitSuccess > 0 ? PERCENT_FORMAT.format((double) deliverSuccess / submitSuccess) : "0.00%";

            SubmitRecordWithStatsDTO dto = new SubmitRecordWithStatsDTO();
            dto.setRecords(merged);
            dto.setSubmitTotal(total);
            dto.setSubmitFailCount(submitFail);
            dto.setSubmitSuccessRate(submitSuccessRate);
            dto.setDeliverSuccessCount(deliverSuccess);
            dto.setDeliverFailCount(deliverFail);
            dto.setDeliverSuccessRate(deliverRate);
            dto.setPageCount(total <= 0 ? 0 : (int) Math.ceil((double) total / FIX_PAGE_SIZE));
            dto.setCurrentPage(pageNum);
            dto.setPageSize(FIX_PAGE_SIZE);
            return dto;

        } finally {
            DynamicDataSourceInterceptor.clearCurrentDataSource();
        }
    }

    /**
     * 导出全部记录（Excel）
     */
    public List<SubmitRecordExportVO> queryExportDataForEasyExcel(
            Date startTime, Date endTime,
            String callee, String mcc, String mnc,
            String innerReportState, String innerReportError,
            String destAccountStatus
    ) {
        DynamicDataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_ECCOM);
        try {
            String subAccount = SubAccount();
            String startTimeStr = String.format("%tY/%<tm/%<td %<tH:%<tM:%<tS", startTime);
            String endTimeStr = String.format("%tY/%<tm/%<td %<tH:%<tM:%<tS", endTime);

            callee = Objects.toString(callee, "").trim();
            mcc = Objects.toString(mcc, "").trim();
            mnc = Objects.toString(mnc, "").trim();
            final String finalInnerReportState = Objects.toString(innerReportState, "").trim();
            destAccountStatus = Objects.toString(destAccountStatus, "").trim();

            List<String> suffixList = getDateSuffixList(startTime, endTime);
            List<SubmitRecordDTO> allSubmitList = new ArrayList<>();
            Map<String, Map<String, Object>> feeMap = new HashMap<>();

            for (String suffix : suffixList) {
                List<SubmitRecordDTO> subList = submitRecordMapper.querySubmitBill(
                        suffix, subAccount, startTimeStr, endTimeStr, callee, mcc, mnc, destAccountStatus
                );
                allSubmitList.addAll(subList);

                List<Map<String, Object>> feeList = submitRecordMapper.queryFeeBill(
                        suffix, subAccount, startTimeStr, endTimeStr, finalInnerReportState, callee, mcc, mnc
                );
                for (Map<String, Object> fee : feeList) {
                    String recvMsgId = (String) fee.get("RecvMsgId");
                    if (recvMsgId != null) {
                        feeMap.put(recvMsgId, fee);
                    }
                }
            }

            return allSubmitList.stream()
                    .filter(sub -> {
                        if (finalInnerReportState.isEmpty()) return true;
                        Map<String, Object> fee = feeMap.get(sub.getSubmitMsgId());
                        return fee != null && finalInnerReportState.equals(String.valueOf(fee.get("InnerReportState")));
                    })
                    .sorted(Comparator.comparing(SubmitRecordDTO::getSubmitMsgTime, Comparator.nullsLast(Comparator.reverseOrder())))
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
                    })
                    .collect(Collectors.toList());

        } finally {
            DynamicDataSourceInterceptor.clearCurrentDataSource();
        }
    }
}