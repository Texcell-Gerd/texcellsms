package com.texcellsms.sms.service;

import com.texcellsms.sms.mapper.eccom.ChargeLogMapper;
import com.texcellsms.sms.model.entity.ChargeLog;
import com.texcellsms.sms.model.entity.CustomerAccount;
import com.texcellsms.sms.util.ChargeLogUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 充值日志服务类
 */
@Service
public class ChargeLogService {

    @Resource
    private ChargeLogMapper chargeLogMapper;

    // 获取当前登录用户的subAccount
    private String SubAccount(){
        return TokenService.getUsername();
    }

    public List<ChargeLog> getChargeLogByUserAccount(String startTime, String endTime) {
        // 1. 校验当前登录用户
        String currentAccount = SubAccount();
        if (currentAccount == null || currentAccount.trim().isEmpty()) {
            throw new RuntimeException("获取登录用户信息失败，请重新登录");
        }

        // 2. 处理默认时间：如果没传，就查最近3天
        if (startTime == null || startTime.trim().isEmpty()) {
            startTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(new Date(System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000));
        }
        if (endTime == null || endTime.trim().isEmpty()) {
            endTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        }

        // 3. 查询客户账号
        List<CustomerAccount> customerAccounts = chargeLogMapper.QueryUserParentAccount(currentAccount);
        if (customerAccounts == null || customerAccounts.isEmpty()) {
            return new ArrayList<>();
        }

        List<ChargeLog> allChargeLogs = new ArrayList<>();

        // 4. 遍历查询（全非空校验）
        for (CustomerAccount customerAccount : customerAccounts) {
            if (customerAccount == null) {
                continue;
            }
            String companyId = customerAccount.getCompanyID();
            if (companyId == null || companyId.trim().isEmpty()) {
                continue;
            }

            // 查询单条数据
            List<ChargeLog> chargeLogs = chargeLogMapper.selectFromMngDB(companyId, startTime, endTime);
            if (chargeLogs == null || chargeLogs.isEmpty()) {
                continue;
            }

            // 处理数据
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
}