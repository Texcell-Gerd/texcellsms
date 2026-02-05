package com.texcellsms.sms.service;


import com.texcellsms.sms.mapper.prepay.SendRecordMapper;

import com.texcellsms.sms.model.DTO.SendRecordDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
/**
 * 发送记录接口
 */
@Service
public class SendRecordService {

    @Resource
    private SendRecordMapper sendRecordMapper;

    /**
     * 成本价换算：ChargeFlag ÷ 10000
     */
    private void calculateActualCost(SendRecordDTO record) {
        if (record.getChargeFlag() != null) {
            // 核心逻辑：除以10000得到真实成本价
            double actualCost = record.getChargeFlag() / 10000.0;
            record.setActualCost(actualCost);
        } else {
            record.setActualCost(0.0); // 无数据时默认0
        }
    }

    /**
     * 通用查询逻辑（处理默认时间+3天限制+成本价换算）
     */
    private List<SendRecordDTO> querySendRecordsCommon(String sourAccount,
                                                       Date startTime, Date endTime,
                                                       String originalCallee, String mcc, String mnc,
                                                       String innerReportState, String innerReportError) {
        // ========== 1. 时间处理核心逻辑 ==========
        Calendar calendar = Calendar.getInstance();
        Date currentTime = new Date();

        // ① 前端未传时间 → 默认查最近1小时
        Date actualStartTime;
        Date actualEndTime = currentTime; // 结束时间默认是当前时间
        if (startTime == null || endTime == null) {
            calendar.add(Calendar.HOUR_OF_DAY, -1); // 最近1小时
            actualStartTime = calendar.getTime();
        } else {
            // ② 前端传了时间 → 强制截断为最近3天
            calendar.add(Calendar.DAY_OF_MONTH, -3);
            Date threeDaysAgo = calendar.getTime();
            // 开始时间：取「前端传的开始时间」和「3天前」的较大值
            actualStartTime = startTime.after(threeDaysAgo) ? startTime : threeDaysAgo;
            // 结束时间：取「前端传的结束时间」和「当前时间」的较小值
            actualEndTime = endTime.before(currentTime) ? endTime : currentTime;
        }

        // ========== 2. 生成近3天的表名后缀（YYYYMMDD） ==========
        calendar.setTime(currentTime);
        calendar.add(Calendar.DAY_OF_MONTH, -3);
        List<String> tableSuffixList = new ArrayList<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
        for (int i = 0; i < 3; i++) {
            calendar.add(Calendar.DAY_OF_MONTH, 1);
            tableSuffixList.add(sdf.format(calendar.getTime()));
        }

        // ========== 3. 调用Mapper查询（指定CDR_DB库） ==========
        List<SendRecordDTO> records = sendRecordMapper.querySendRecords(
                tableSuffixList,
                actualStartTime,
                actualEndTime,
                sourAccount,
                originalCallee,
                mcc,
                mnc,
                innerReportState,
                innerReportError
        );

        // ========== 4. 批量换算成本价 ==========
        for (SendRecordDTO record : records) {
            calculateActualCost(record);
        }

        return records;
    }

    /**
     * 线上版：根据当前登录用户查询（默认1小时，最多3天）
     */
    public List<SendRecordDTO> getSendRecordsByLoginUser(String loginUserAccount,
                                                         Date startTime, Date endTime,
                                                         String originalCallee, String mcc, String mnc,
                                                         String innerReportState, String innerReportError) {
        return querySendRecordsCommon(loginUserAccount, startTime, endTime,
                originalCallee, mcc, mnc, innerReportState, innerReportError);
    }

    /**
     * 调试版：支持指定用户账号查询（默认1小时，最多3天）
     */
    public List<SendRecordDTO> getSendRecordsForDebug(String sourAccount,
                                                      Date startTime, Date endTime,
                                                      String originalCallee, String mcc, String mnc,
                                                      String innerReportState, String innerReportError) {
        return querySendRecordsCommon(sourAccount, startTime, endTime,
                originalCallee, mcc, mnc, innerReportState, innerReportError);
    }
}