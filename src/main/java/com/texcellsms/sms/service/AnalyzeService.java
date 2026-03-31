package com.texcellsms.sms.service;

import com.texcellsms.sms.mapper.eccom.AnalyzeMapper;
import com.texcellsms.sms.model.DTO.AnalyzeDTO;
import com.texcellsms.sms.model.DTO.DailyStatDTO;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.*;

@Service
public class AnalyzeService {

    @Resource
    private AnalyzeMapper analyzeMapper;
    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

    // 获取当前登录用户的subAccount
    private String SubAccount(){
        return TokenService.getUsername();
    }

    public List<AnalyzeDTO> query(Date start, Date end,
                                  String mcc, String mnc, int pageNum, int pageSize) {

        String account = SubAccount();

        if (start == null) start = new Date(System.currentTimeMillis() - 7*86400000L);
        if (end == null) end = new Date();

        String startTime = sdf.format(start);
        String endTime = sdf.format(end);
        int offset = (pageNum - 1) * pageSize;

        List<AnalyzeDTO> list = analyzeMapper.selectAnalyzePage(
                account, startTime, endTime, mcc, mnc, offset, pageSize);

        // 金额统一 /10000
        for (AnalyzeDTO dto : list) {
            if (dto.getTotalCost() != null) {
                dto.setTotalCost(dto.getTotalCost()
                        .divide(new BigDecimal(10000), 4, RoundingMode.HALF_UP));
            }
        }
        return list;
    }

    public int total(Date start, Date end, String mcc, String mnc) {

        String account = SubAccount();

        if (start == null) start = new Date(System.currentTimeMillis() - 7*86400000L);
        if (end == null) end = new Date();
        return analyzeMapper.countAnalyze(account, sdf.format(start), sdf.format(end), mcc, mnc);
    }

    public List<DailyStatDTO> getDailyStat(String period, String mcc) {
        String account = SubAccount();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        Calendar cal = Calendar.getInstance();
        Date now = new Date();

        // 结束日期 = 今天
        String endDate = sdf.format(now);

        // 计算开始日期
        switch (period) {
            case "today":
                cal.setTime(now);
                cal.add(Calendar.DATE, -3);
                break;
            case "week":
                cal.setTime(now);
                cal.add(Calendar.DATE, -7);
                break;
            case "Two-weeks":
                cal.setTime(now);
                cal.add(Calendar.DATE, -14);
                break;
            default:
                cal.add(Calendar.DATE, -7);
        }

        String startDate = sdf.format(cal.getTime());

        // 调用修复后的 Mapper
        List<AnalyzeDTO> rawList = analyzeMapper.selectAnalyzeList(
                account, startDate, endDate, mcc, null);

        Map<String, DailyStatDTO> map = new LinkedHashMap<>();
        for (AnalyzeDTO dto : rawList) {
            String day = dto.getSubmitTime();
            DailyStatDTO stat = map.getOrDefault(day, new DailyStatDTO());
            stat.setDate(day);

            int sc = dto.getSubmitCount() == null ? 0 : dto.getSubmitCount();
            stat.setSubmitCount((stat.getSubmitCount() == null ? 0 : stat.getSubmitCount()) + sc);

            BigDecimal cost = dto.getTotalCost() == null ? BigDecimal.ZERO : dto.getTotalCost();
            BigDecimal realCost = cost.divide(new BigDecimal(10000), 4, RoundingMode.HALF_UP);
            stat.setTotalCost(stat.getTotalCost() == null ? realCost : stat.getTotalCost().add(realCost));

            map.put(day, stat);
        }

        return new ArrayList<>(map.values());
    }
}