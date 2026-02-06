package com.texcellsms.sms.service;

import com.texcellsms.sms.mapper.prepay.SubmitRecordMapper;
import com.texcellsms.sms.model.DTO.SubmitRecordDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

@Service
public class SubmitRecordService {

    @Resource
    private SubmitRecordMapper submitRecordMapper;

    /**
     * 计费金额换算：ChargeFee ÷ 10000
     */
    private void calculateActualChargeFee(SubmitRecordDTO record) {
        if (record.getChargeFee() != null && !record.getChargeFee().trim().isEmpty()) {
            try {
                double actualChargeFee = Double.parseDouble(record.getChargeFee()) / 10000.0;
                record.setActualChargeFee(actualChargeFee);
            } catch (NumberFormatException e) {
                record.setActualChargeFee(0.0);
            }
        } else {
            record.setActualChargeFee(0.0);
        }
    }

    /**
     * 通用查询逻辑（处理默认时间+3天限制+金额换算）
     */
    private List<SubmitRecordDTO> querySubmitRecordsCommon(String sourAccount,
                                                           Date startTime, Date endTime,
                                                           String callee, String mcc, String mnc,
                                                           String innerReportState, String innerReportError) {
        Calendar calendar = Calendar.getInstance();
        Date currentTime = new Date();

        // 时间处理
        Date actualStartTime;
        Date actualEndTime = currentTime;
        if (startTime == null || endTime == null) {
            calendar.add(Calendar.HOUR_OF_DAY, -1);
            actualStartTime = calendar.getTime();
        } else {
            calendar.add(Calendar.DAY_OF_MONTH, -3);
            Date threeDaysAgo = calendar.getTime();
            actualStartTime = startTime.after(threeDaysAgo) ? startTime : threeDaysAgo;
            actualEndTime = endTime.before(currentTime) ? endTime : currentTime;
        }

        // 生成近3天表后缀
        calendar.setTime(currentTime);
        calendar.add(Calendar.DAY_OF_MONTH, -3);
        List<String> tableSuffixList = new ArrayList<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
        for (int i = 0; i < 3; i++) {
            calendar.add(Calendar.DAY_OF_MONTH, 1);
            tableSuffixList.add(sdf.format(calendar.getTime()));
        }

        // 调用Mapper
        List<SubmitRecordDTO> records = submitRecordMapper.querySubmitRecords(
                tableSuffixList,
                actualStartTime,
                actualEndTime,
                sourAccount,
                callee,
                mcc,
                mnc,
                innerReportState,
                innerReportError
        );

        // 批量换算金额
        for (SubmitRecordDTO record : records) {
            calculateActualChargeFee(record);
        }

        return records;
    }

    /**
     * 线上版：根据当前登录用户查询
     */
    public List<SubmitRecordDTO> getSubmitRecordsByLoginUser(String loginUserAccount,
                                                             Date startTime, Date endTime,
                                                             String callee, String mcc, String mnc,
                                                             String innerReportState, String innerReportError) {
        return querySubmitRecordsCommon(loginUserAccount, startTime, endTime,
                callee, mcc, mnc, innerReportState, innerReportError);
    }

    /**
     * 调试版：支持指定用户账号查询
     */
    public List<SubmitRecordDTO> getSubmitRecordsForDebug(String sourAccount,
                                                          Date startTime, Date endTime,
                                                          String callee, String mcc, String mnc,
                                                          String innerReportState, String innerReportError) {
        return querySubmitRecordsCommon(sourAccount, startTime, endTime,
                callee, mcc, mnc, innerReportState, innerReportError);
    }
}