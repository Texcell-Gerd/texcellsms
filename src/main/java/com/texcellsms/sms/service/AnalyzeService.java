package com.texcellsms.sms.service;

import com.texcellsms.sms.config.DynamicDataSourceConfig;
import com.texcellsms.sms.interceptor.DynamicDataSourceInterceptor;
import com.texcellsms.sms.mapper.prepay.AnalyzeMapper;
import com.texcellsms.sms.model.DTO.AnalyzeDTO;
import com.texcellsms.sms.model.DTO.PItemDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@Service
public class AnalyzeService {

    private static final Logger logger = LoggerFactory.getLogger(AnalyzeService.class);

    @Resource
    private AnalyzeMapper analyzeMapper;

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
     * 查询方法
     */
    public AnalyzeDTO queryAnalyzeData(
            String sourAccount, Date startTime, Date endTime,
            String mcc, String mnc,
            Integer pageNum, Integer pageSize
    ) {
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
            pageSize = Math.min(pageSize, 50);
            Integer offset = (pageNum - 1) * pageSize;

            List<PItemDTO> pItemDTOS = analyzeMapper.queryAnalyzeData(
                    sourAccount, startTimeStr, endTimeStr, mcc, mnc, pageSize, offset
            );
            AnalyzeDTO analyzeDTO = new AnalyzeDTO();
            analyzeDTO.setPItem(pItemDTOS);
            return analyzeDTO;
        } finally {
            DynamicDataSourceInterceptor.clearCurrentDataSource();
        }
    }
}
