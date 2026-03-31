package com.texcellsms.sms.service;

import com.texcellsms.sms.mapper.eccom.ChargeLogMapper;
import com.texcellsms.sms.model.entity.ChargeLog;
import com.texcellsms.sms.model.entity.CustomerAccount;
import com.texcellsms.sms.util.ChargeLogUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;

/**
 * 充值日志服务类
 */
@Service
public class ChargeLogService {

    @Resource
    private ChargeLogMapper chargeLogMapper;

    /**
     * 获取当前登录用户账号
     */
    private String SubAccount(){
        return TokenService.getUsername();
    }

    /**
     * 根据用户账号查询充值记录（内部通用方法）
     */
    public List<ChargeLog> getChargeLogByUserAccount(String startTime, String endTime) {
        // 1. 校验当前登录用户
        String currentAccount = SubAccount();
        if (currentAccount == null || currentAccount.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 处理默认时间（线程安全）
        if (startTime == null || startTime.trim().isEmpty()) {
            startTime = String.format("%tF %<tT", new Date(System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000));
        }
        if (endTime == null || endTime.trim().isEmpty()) {
            endTime = String.format("%tF %<tT", new Date());
        }

        // 3. 查询客户账号
        List<CustomerAccount> customerAccounts;
        try {
            customerAccounts = chargeLogMapper.QueryUserParentAccount(currentAccount);
        } catch (Exception e) {
            return Collections.emptyList();
        }
        if (customerAccounts == null || customerAccounts.isEmpty()) {
            return Collections.emptyList();
        }

        List<ChargeLog> allChargeLogs = new ArrayList<>();

        // 4. 遍历查询
        for (CustomerAccount customerAccount : customerAccounts) {
            if (customerAccount == null) continue;

            String companyId = customerAccount.getCompanyID();
            if (companyId == null || companyId.trim().isEmpty()) continue;

            List<ChargeLog> chargeLogs;
            try {
                chargeLogs = chargeLogMapper.selectFromMngDB(companyId, startTime, endTime);
            } catch (Exception e) {
                continue;
            }
            if (chargeLogs == null || chargeLogs.isEmpty()) continue;

            // 封装字段
            for (ChargeLog log : chargeLogs) {
                if (log == null) continue;

                log.setRemark(ChargeLogUtils.hexToChinese(log.getRemark()));
                log.setPrevBalance(ChargeLogUtils.convertBalance(log.getPrevBalance()));
                log.setPostBalance(ChargeLogUtils.convertBalance(log.getPostBalance()));
                log.setAmount(ChargeLogUtils.convertBalance(log.getAmount()));
            }

            allChargeLogs.addAll(chargeLogs);
        }

        return allChargeLogs;
    }

    /**
     * 分页查询充值记录
     */
    public List<ChargeLog> getChargeLogPage(String startTime, String endTime, Integer pageNum, Integer pageSize) {
        List<ChargeLog> all = getChargeLogByUserAccount(startTime, endTime);
        if (all.isEmpty()) return Collections.emptyList();

        int start = (pageNum - 1) * pageSize;
        int end = Math.min(start + pageSize, all.size());

        if (start >= all.size()) return Collections.emptyList();
        return all.subList(start, end);
    }

    /**
     * 获取充值记录总数
     */
    public int getChargeLogCount(String startTime, String endTime) {
        return getChargeLogByUserAccount(startTime, endTime).size();
    }
}