package com.texcellsms.sms.service.impl;

import CacheDB.NCacheDBLink;
import com.texcellsms.sms.controller.AuthController;
import com.texcellsms.sms.model.vo.CacheDBLonginResult;
import com.texcellsms.sms.model.vo.LoginUser;
import com.texcellsms.sms.service.AuthService;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.AjaxResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.texcellsms.sms.util.SMSResponseMessage.*;

@Service
public class AuthServiceImpl implements AuthService {

    // 你的 Logger 完全对齐
    private static final Logger logger = LoggerFactory.getLogger(AuthServiceImpl.class);

    /**
     * 用户登录接口
     */
    @Override
    public AjaxResult login(AuthController.LoginRequest loginRequest, String IP) {
        TokenService tokenService = new TokenService();
        LoginUser loginUser = new LoginUser();
        loginUser.setUsername(loginRequest.getUsername());
        loginUser.setPassword(loginRequest.getPassword());
        loginUser.setIP(IP);

        CacheDBLonginResult cacheDBLonginResult = tokenService.getToken(loginUser);

        // ====================== 登录结果为空 ======================
        if (cacheDBLonginResult == null) {
            logger.error("[LOGIN_FAIL] USER={} | IP={} | CODE=NULL | MSG=Authentication failed",
                    loginRequest.getUsername(), IP);
            return AjaxResult.error();
        }

        // ====================== 业务错误码处理 ======================
        if (!"0".equals(cacheDBLonginResult.getResult())) {
            String code = cacheDBLonginResult.getResult();
            String msg = "";

            switch (code) {
                case "405":
                    msg = PARAMETERERROR.getMessage();
                    logger.error("[LOGIN_FAIL] USER={} | IP={} | CODE=405 | MSG={}",
                            loginRequest.getUsername(), IP, msg);
                    return new AjaxResult(PARAMETERERROR.getCode(), msg);
                case "502":
                    msg = CACHEDBERROR.getMessage();
                    logger.error("[LOGIN_FAIL] USER={} | IP={} | CODE=502 | MSG={}",
                            loginRequest.getUsername(), IP, msg);
                    return new AjaxResult(CACHEDBERROR.getCode(), msg);
                case "403":
                    msg = AUTHENTICATIONERROR.getMessage();
                    logger.error("[LOGIN_FAIL] USER={} | IP={} | CODE=403 | MSG={}",
                            loginRequest.getUsername(), IP, msg);
                    return new AjaxResult(AUTHENTICATIONERROR.getCode(), msg);
                case "603":
                    msg = ACCOUNTLOCKEDERROR.getMessage();
                    logger.error("[LOGIN_FAIL] USER={} | IP={} | CODE=603 | MSG={}",
                            loginRequest.getUsername(), IP, msg);
                    return new AjaxResult(ACCOUNTLOCKEDERROR.getCode(), msg);
                case "402":
                    msg = ACCOUNTERROR.getMessage();
                    logger.error("[LOGIN_FAIL] USER={} | IP={} | CODE=402 | MSG={}",
                            loginRequest.getUsername(), IP, msg);
                    return new AjaxResult(ACCOUNTERROR.getCode(), msg);
                case "480":
                    msg = IPLOCKEDERROR.getMessage();
                    logger.error("[LOGIN_FAIL] USER={} | IP={} | CODE=480 | MSG={}",
                            loginRequest.getUsername(), IP, msg);
                    return new AjaxResult(IPLOCKEDERROR.getCode(), msg);
                default:
                    msg = "Login failed";
                    logger.error("[LOGIN_FAIL] USER={} | IP={} | CODE={} | MSG={}",
                            loginRequest.getUsername(), IP, code, msg);
                    return new AjaxResult(Integer.parseInt(code), msg);
            }
        }

        // ====================== IP白名单校验（支持多IP） ======================
        String whiteIP = cacheDBLonginResult.getWhitelistIP();
        boolean ipAllowed;

        if (whiteIP == null || whiteIP.trim().isEmpty()) {
            ipAllowed = true;
        } else if ("%".equals(whiteIP.trim())) {
            ipAllowed = true;
        } else {
            String[] allowedIPs = whiteIP.split("[;,\\s]+");
            ipAllowed = false;
            for (String allowedIp : allowedIPs) {
                if (allowedIp.trim().equals(IP)) {
                    ipAllowed = true;
                    break;
                }
            }
        }

        // IP 不匹配
        if (!ipAllowed) {
            String msg = "IP address not in whitelist";
            logger.error("[LOGIN_FAIL] USER={} | IP={} | WHITELIST={} | MSG={}",
                    loginRequest.getUsername(), IP, whiteIP, msg);
            return new AjaxResult(480, msg);
        }

        // ====================== 登录成功 ======================
        String safeToken = java.util.UUID.randomUUID().toString().replace("-", "");
        loginUser.setToken(safeToken);
        loginUser.setCreateTime(System.currentTimeMillis());
        tokenService.createToken(loginUser);

        // ✅ 这个格式 100% 匹配监控面板，会自动进入登录日志
        logger.info("[LOGIN_SUCCESS] USER={} | IP={} | WHITELIST={} | MSG=Login success",
                loginRequest.getUsername(), IP, whiteIP);

        return AjaxResult.success(loginUser);
    }


    /**
     * 查询账户余额
     */
    @Override
    public AjaxResult getBalance(String Username) {
        NCacheDBLink nCacheDBLink = null;
        int count = -1;
        try {
            nCacheDBLink = NCacheDBLink.getNCacheDBLink();
            count = nCacheDBLink.InitEnv();
            nCacheDBLink.ConnectToCacheDB();
            Map<String, Object> map = nCacheDBLink.prc_MMSIFQueryBalance(Username);

            BigDecimal b1 = new BigDecimal((String) map.get("Balance"));
            BigDecimal result = b1.divide(new BigDecimal("10000"));
            map.put("Balance", result);
            return AjaxResult.success(map);

        } catch (Exception e) {
            e.printStackTrace();
            return AjaxResult.error("Failed to query balance");
        } finally {
            // 【重要】必须保证释放连接，否则线上OOM
            if (nCacheDBLink != null|| count>0) {
                try { nCacheDBLink.Disconnect(); } catch (Exception ignored) {}
                try { nCacheDBLink.Destroy(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 根据MCC+MNC查询费率
     */
    @Override
    public AjaxResult getRate(String Username, String MCC, String MNC) {
        NCacheDBLink nCacheDBLink = null;
        int count = -1;
        try {
            nCacheDBLink = NCacheDBLink.getNCacheDBLink();
            count=nCacheDBLink.InitEnv("192.168.112.250", 18801, "auth", "auth", "auth", "utf-8", "utf-8");
            nCacheDBLink.ConnectToCacheDB();
            List<Map<String, String>> list = nCacheDBLink.web_QueryUserRate(Username, MCC, MNC);
            return AjaxResult.success(list);

        } catch (Exception e) {
            e.printStackTrace();
            return AjaxResult.error("Failed to query rate");
        } finally {
            // 保证释放
            if (nCacheDBLink != null|| count>0) {
                try { nCacheDBLink.Disconnect(); } catch (Exception ignored) {}
                try { nCacheDBLink.Destroy(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 修改子账户密码
     */
    @Override
    public AjaxResult ChangeSwitchAccountPWD(String Username, String OldPass, String NewPass) {
        NCacheDBLink nCacheDBLink = null;
        int count = -1;
        try {
            nCacheDBLink = NCacheDBLink.getNCacheDBLink();
            count=nCacheDBLink.InitEnv();
            nCacheDBLink.ConnectToCacheDB();
            Map<String, Object> map = nCacheDBLink.prc_ChangeSwitchAccountPWD(Username, OldPass, NewPass);
            return AjaxResult.success(map);

        } catch (Exception e) {
            e.printStackTrace();
            return AjaxResult.error("Failed to change password");
        } finally {
            // 保证释放
            if (nCacheDBLink != null || count>0) {
                try { nCacheDBLink.Disconnect(); } catch (Exception ignored) {}
                try { nCacheDBLink.Destroy(); } catch (Exception ignored) {}
            }
        }
    }
}