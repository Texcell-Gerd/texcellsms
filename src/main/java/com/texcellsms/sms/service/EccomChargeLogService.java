package com.texcellsms.sms.service;

import com.texcellsms.sms.mapper.eccom.EccomChargeLogMapper;
import com.texcellsms.sms.model.entity.ChargeLog;
import com.texcellsms.sms.util.ChargeLogUtils;

import org.springframework.stereotype.Service;


import javax.annotation.Resource;
import java.util.List;

/**
 * eccom 充值日志服务类
 */
@Service
public class EccomChargeLogService {

    @Resource
    private EccomChargeLogMapper eccomChargeLogMapper;

    public List<ChargeLog> getChargeLogByUserAccount(String account) {
        // 若eccom有专属数据源，替换为selectFromEccomDB(account)
        List<ChargeLog> chargeLogs = eccomChargeLogMapper.selectFromMngDB(account);

        // 复用prepay的处理逻辑：备注转中文、余额换算
        for (ChargeLog log : chargeLogs) {
            log.setRemark(ChargeLogUtils.hexToChinese(log.getRemark()));
            log.setPrevBalance(ChargeLogUtils.convertBalance(log.getPrevBalance()));
            log.setPostBalance(ChargeLogUtils.convertBalance(log.getPostBalance()));
            log.setAmount(ChargeLogUtils.convertBalance(log.getAmount()));
        }
        return chargeLogs;
    }
}