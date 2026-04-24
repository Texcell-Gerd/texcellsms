package com.texcellsms.sms.service;

import com.texcellsms.sms.config.DynamicDataSourceConfig;
import com.texcellsms.sms.interceptor.DynamicDataSourceInterceptor;
import com.texcellsms.sms.mapper.eccom.SubmitRecordMapper;
import com.texcellsms.sms.model.DTO.SubmitRecordDTO;
import com.texcellsms.sms.model.DTO.SubmitRecordWithStatsDTO;
import com.texcellsms.sms.model.vo.SubmitRecordExportVO;
import com.texcellsms.sms.util.Utf8ToGsm7;
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

    private String SubAccount() {
        return TokenService.getUsername();
    }

    private static final NumberFormat PERCENT_FORMAT;
    static {
        PERCENT_FORMAT = NumberFormat.getPercentInstance(Locale.US);
        PERCENT_FORMAT.setMaximumFractionDigits(2);
        PERCENT_FORMAT.setGroupingUsed(false);
    }

    /**
     * 获取日期表后缀列表（按天分表）【不查未来表】
     * 获取日期表后缀列表（按天分表）【修复跨天查询】
     */
    private List<String> getDateSuffixList(Date start, Date end) {
        List<String> suffixList = new ArrayList<>();
        if (start == null || end == null) {
            return suffixList;
        }

        Calendar cal = Calendar.getInstance();
        cal.setTime(start);
        // 把开始时间重置到当天 00:00:00，只按日期判断，不看具体时间
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        // 循环条件改为：只要当前日期的 0点 不晚于结束日期的 0点，就继续
        Calendar endCal = Calendar.getInstance();
        endCal.setTime(end);
        endCal.set(Calendar.HOUR_OF_DAY, 0);
        endCal.set(Calendar.MINUTE, 0);
        endCal.set(Calendar.SECOND, 0);
        endCal.set(Calendar.MILLISECOND, 0);

        while (!cal.getTime().after(endCal.getTime())) {
            suffixList.add(String.format("%tY%<tm%<td", cal.getTime()));
            cal.add(Calendar.DATE, 1);
        }

        return suffixList;
    }
    /**
     * 检查表是否存在
     */
    private boolean isTableExist(String tableName) {
        try {
            return submitRecordMapper.checkTableExist(tableName) > 0;
        } catch (Exception e) {
            return false;
        }
    }

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
            Map<String, String> msgIdToTableSuffix = new HashMap<>();
            Map<String, String> msgIdToSubmitTime = new HashMap<>();
            List<Map<String, Object>> allIdWithTime = new ArrayList<>();

            // ====================== 【核心：150W 数据拦截】 ======================
            for (String suffix : suffixList) {
                String table = "Submit_Bill_" + suffix;
                if (!isTableExist(table)) continue;

                // 生成当前表 当天 00:00:00 ~ 23:59:59
                String tableDate = suffix; // 20260423
                String tableStart = tableDate.substring(0,4) + "/" + tableDate.substring(4,6) + "/" + tableDate.substring(6,8) + " 00:00:00";
                String tableEnd   = tableDate.substring(0,4) + "/" + tableDate.substring(4,6) + "/" + tableDate.substring(6,8) + " 23:59:59";

                // 再和用户传入的时间取交集
                String finalStart = startTimeStr.compareTo(tableStart) > 0 ? startTimeStr : tableStart;
                String finalEnd   = endTimeStr.compareTo(tableEnd) < 0 ? endTimeStr : tableEnd;

                List<Map<String, Object>> idWithTimeList = submitRecordMapper.listSubmitMsgIdsWithTime(
                        suffix, subAccount, finalStart, finalEnd,
                        callee, mcc, mnc, destAccountStatus
                );
                allIdWithTime.addAll(idWithTimeList);

                // 超过 150万 → 立即中断，抛出异常信息
                if (allIdWithTime.size() > 1500000) {
                    throw new RuntimeException("The data volume is too large, please contact staff to query.");
                }

                for (Map<String, Object> map : idWithTimeList) {
                    String msgId = (String) map.get("SubmitMsgId");
                    String submitTime = (String) map.get("SubmitMsgTime");
                    msgIdToTableSuffix.put(msgId, suffix);
                    msgIdToSubmitTime.put(msgId, submitTime);
                }
            }

            // 回执查询
            Map<String, Map<String, Object>> feeMap = new HashMap<>();
            for (String suffix : suffixList) {
                String table = "Fee_Bill_" + suffix;
                if (!isTableExist(table)) continue;

                List<Map<String, Object>> feeList = submitRecordMapper.queryFeeBill(
                        suffix, subAccount, finalInnerReportState, callee, mcc, mnc
                );
                for (Map<String, Object> fee : feeList) {
                    String recvMsgId = (String) fee.get("RecvMsgId");
                    if (recvMsgId != null) {
                        feeMap.put(recvMsgId, fee);
                    }
                }
            }

            List<String> validIds = new ArrayList<>();
            for (Map<String, Object> map : allIdWithTime) {
                String msgId = (String) map.get("SubmitMsgId");
                if (!finalInnerReportState.isEmpty() && !feeMap.containsKey(msgId)) {
                    continue;
                }
                validIds.add(msgId);

            }

            // 内存排序（保留原有逻辑）
            validIds.sort((id1, id2) -> {
                String t1 = msgIdToSubmitTime.get(id1);
                String t2 = msgIdToSubmitTime.get(id2);
                if (t1 == null) return 1;
                if (t2 == null) return -1;
                return t2.compareTo(t1);
            });

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

            boolean hasSearchCondition =
                    (callee != null && !callee.trim().isEmpty())
                            || (mcc != null && !mcc.trim().isEmpty())
                            || (mnc != null && !mnc.trim().isEmpty())
                            || (destAccountStatus != null && !destAccountStatus.trim().isEmpty())
                            || (finalInnerReportState != null && !finalInnerReportState.trim().isEmpty());

            if (hasSearchCondition) {
                pageNum = 1;
            }

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
                    List<String> ids = entry.getValue();
                    if (ids.size() > 1000) ids = ids.subList(0, 1000);
                    pageRecords.addAll(submitRecordMapper.listByIds(entry.getKey(), ids));
                }
            }

            pageRecords.sort(Comparator.comparing(SubmitRecordDTO::getSubmitMsgTime, Comparator.nullsLast(Comparator.reverseOrder())));

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
                    String content = decodeLongSmsForFrontend(sub.getOriginalShortMessage(), sub.getDcs());
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

    // ====================== 导出功能：完全不动 ======================
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
                if (!isTableExist("Submit_Bill_" + suffix)) continue;

                List<SubmitRecordDTO> subList = submitRecordMapper.querySubmitBill(
                        suffix, subAccount, startTimeStr, endTimeStr, callee, mcc, mnc, destAccountStatus
                );
                allSubmitList.addAll(subList);

                if (!isTableExist("Fee_Bill_" + suffix)) continue;
                List<Map<String, Object>> feeList = submitRecordMapper.queryFeeBill(
                        suffix, subAccount, finalInnerReportState, callee, mcc, mnc
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
                            String content = decodeLongSmsForExport(sub.getOriginalShortMessage(), sub.getDcs());
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

    private String decodeLongSmsForFrontend(String hexMessage, String dcsStr) {
        if (hexMessage == null || hexMessage.isEmpty()) return "";
        try {
            int dcs = Integer.parseInt(dcsStr);
            String contentHex = hexMessage;
            if (contentHex.length() >= 12 && contentHex.startsWith("050003")) {
                contentHex = contentHex.substring(12);
            }
            byte[] bytes = hexStringToBytes(contentHex);
            String content;
            if (dcs == 8) {
                content = new String(bytes, "UTF-16BE");
            } else {
                content = Utf8ToGsm7.decode(bytes);
            }
            return Base64.getEncoder().encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.error("短信解析异常: HEX={}, DCS={}", hexMessage, dcsStr, e);
            return hexMessage;
        }
    }

    private String decodeLongSmsForExport(String hexMessage, String dcsStr) {
        if (hexMessage == null || hexMessage.isEmpty()) return "";
        try {
            int dcs = Integer.parseInt(dcsStr);
            String contentHex = hexMessage;
            if (contentHex.length() >= 12 && contentHex.startsWith("050003")) {
                contentHex = contentHex.substring(12);
            }
            byte[] bytes = hexStringToBytes(contentHex);
            if (dcs == 8) {
                return new String(bytes, "UTF-16BE");
            } else {
                return Utf8ToGsm7.decode(bytes);
            }
        } catch (Exception e) {
            logger.error("短信解析异常: HEX={}, DCS={}", hexMessage, dcsStr, e);
            return hexMessage;
        }
    }

    private byte[] hexStringToBytes(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }
}