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
            return AjaxResult.error();
        }

        if (!"0".equals(cacheDBLonginResult.getResult())) {
            switch (cacheDBLonginResult.getResult()) {
                case "405":
                    return new AjaxResult(PARAMETERERROR.getCode(), PARAMETERERROR.getMessage());
                case "502":
                    return new AjaxResult(CACHEDBERROR.getCode(), CACHEDBERROR.getMessage());
                case "403":
                    return new AjaxResult(AUTHENTICATIONERROR.getCode(), AUTHENTICATIONERROR.getMessage());
                case "603":
                    return new AjaxResult(ACCOUNTLOCKEDERROR.getCode(), ACCOUNTLOCKEDERROR.getMessage());
                case "402":
                    return new AjaxResult(ACCOUNTERROR.getCode(), ACCOUNTERROR.getMessage());
                case "480":
                    return new AjaxResult(IPLOCKEDERROR.getCode(), IPLOCKEDERROR.getMessage());
                default:
                    return new AjaxResult(Integer.parseInt(cacheDBLonginResult.getResult()), "");
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
        try {
            nCacheDBLink = NCacheDBLink.getNCacheDBLink();
            nCacheDBLink.InitEnv();
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
            if (nCacheDBLink != null) {
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
        try {
            nCacheDBLink = NCacheDBLink.getNCacheDBLink();
            nCacheDBLink.InitEnv("192.168.120.250", 18801, "auth", "auth", "auth", "utf-8", "utf-8");
            nCacheDBLink.ConnectToCacheDB();
            List<Map<String, String>> list = nCacheDBLink.web_QueryUserRate(Username, MCC, MNC);
            return AjaxResult.success(list);

        } catch (Exception e) {
            e.printStackTrace();
            return AjaxResult.error("Failed to query rate");
        } finally {
            // 保证释放
            if (nCacheDBLink != null) {
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
        try {
            nCacheDBLink = NCacheDBLink.getNCacheDBLink();
            nCacheDBLink.InitEnv();
            nCacheDBLink.ConnectToCacheDB();
            Map<String, Object> map = nCacheDBLink.prc_ChangeSwitchAccountPWD(Username, OldPass, NewPass);
            return AjaxResult.success(map);

        } catch (Exception e) {
            e.printStackTrace();
            return AjaxResult.error("Failed to change password");
        } finally {
            // 保证释放
            if (nCacheDBLink != null) {
                try { nCacheDBLink.Disconnect(); } catch (Exception ignored) {}
                try { nCacheDBLink.Destroy(); } catch (Exception ignored) {}
            }
        }
    }
}