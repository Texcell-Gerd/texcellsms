package com.texcellsms.sms.service.impl;

import CacheDB.NCacheDBLink;
import com.texcellsms.sms.controller.AuthController;
import com.texcellsms.sms.model.vo.CacheDBLonginResult;
import com.texcellsms.sms.model.vo.LoginUser;
import com.texcellsms.sms.service.AuthService;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.AjaxResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.texcellsms.sms.util.SMSResponseMessage.*;

@Service
public class AuthServiceImpl implements AuthService {

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

        // 生成安全TOKEN
        String safeToken = UUID.randomUUID().toString().replace("-", "");
        loginUser.setToken(safeToken);
        loginUser.setCreateTime(System.currentTimeMillis());
        tokenService.createToken(loginUser);

        if (cacheDBLonginResult == null) {
            // ====================== 登录失败：打印日志 ======================
            System.out.format("[LOGIN_FAIL] USER=%s | IP=%s | CODE=NULL | MSG=登录验证失败%n",
                    loginRequest.getUsername(), IP);
            return AjaxResult.error();
        }

        if (!"0".equals(cacheDBLonginResult.getResult())) {
            String code = cacheDBLonginResult.getResult();
            String msg = "";

            switch (code) {
                case "405":
                    msg = PARAMETERERROR.getMessage();
                    return new AjaxResult(PARAMETERERROR.getCode(), msg);
                case "502":
                    msg = CACHEDBERROR.getMessage();
                    return new AjaxResult(CACHEDBERROR.getCode(), msg);
                case "403":
                    msg = AUTHENTICATIONERROR.getMessage();
                    return new AjaxResult(AUTHENTICATIONERROR.getCode(), msg);
                case "603":
                    msg = ACCOUNTLOCKEDERROR.getMessage();
                    return new AjaxResult(ACCOUNTLOCKEDERROR.getCode(), msg);
                case "402":
                    msg = ACCOUNTERROR.getMessage();
                    return new AjaxResult(ACCOUNTERROR.getCode(), msg);
                case "480":
                    msg = IPLOCKEDERROR.getMessage();
                    // ====================== IP锁定：打印日志 ======================
                    System.out.format("[LOGIN_FAIL] USER=%s | IP=%s | CODE=480 | MSG=%s%n",
                            loginRequest.getUsername(), IP, msg);
                    return new AjaxResult(IPLOCKEDERROR.getCode(), msg);
                default:
                    msg = "登录失败";
                    // ====================== 其他错误：打印日志 ======================
                    System.out.format("[LOGIN_FAIL] USER=%s | IP=%s | CODE=%s | MSG=%s%n",
                            loginRequest.getUsername(), IP, code, msg);
                    return new AjaxResult(Integer.parseInt(code), "");
            }
        }
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