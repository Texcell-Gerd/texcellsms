package com.texcellsms.sms.service;

import com.texcellsms.sms.mapper.prepay.ChargeLogMapper;
import com.texcellsms.sms.model.entity.ChargeLog;
import com.texcellsms.sms.util.ChargeLogUtils;


import org.springframework.stereotype.Service;


import javax.annotation.Resource;
import java.util.List;

/**
 * 充值日志服务类
 */
@Service
public class ChargeLogService {

    @Resource
    private ChargeLogMapper chargeLogMapper;

    public List<ChargeLog> getChargeLogByUserAccount(String account) {
        List<ChargeLog> chargeLogs = chargeLogMapper.selectFromMngDB(account);

        for (ChargeLog log : chargeLogs) {
            // 1. 解析备注为中文
            log.setRemark(ChargeLogUtils.hexToChinese(log.getRemark()));
            // 2. 余额换算
            log.setPrevBalance(ChargeLogUtils.convertBalance(log.getPrevBalance()));
            log.setPostBalance(ChargeLogUtils.convertBalance(log.getPostBalance()));
            log.setAmount(ChargeLogUtils.convertBalance(log.getAmount()));
        }
        return chargeLogs;
    }
}