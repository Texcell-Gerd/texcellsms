package com.texcellsms.sms.service.impl;

import CacheDB.NCacheDBLink;
import com.texcellsms.sms.controller.AuthController;
import com.texcellsms.sms.model.vo.CacheDBLonginResult;
import com.texcellsms.sms.model.vo.LoginUser;
import com.texcellsms.sms.service.AuthService;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.AjaxResult;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.texcellsms.sms.util.SMSResponseMessage.*;

@Service
public class AuthServiceImpl implements AuthService {

    @Override
    public AjaxResult login(AuthController.LoginRequest loginRequest,String IP) {
        TokenService tokenService=new TokenService();
        LoginUser loginUser=new LoginUser();
        loginUser.setUsername(loginRequest.getUsername());
        loginUser.setPassword(loginRequest.getPassword());
        loginUser.setIP(IP);
        CacheDBLonginResult cacheDBLonginResult = tokenService.getToken(loginUser);
        loginUser.setUUID(UUID.randomUUID().toString().replace("-", ""));
        loginUser.setToken(loginUser.getUUID()+cacheDBLonginResult.getFAKey());
        tokenService.createToken(loginUser);
        if (cacheDBLonginResult==null){
            return AjaxResult.success();
        }
        if (!cacheDBLonginResult.getResult().equals("0")){
            switch (cacheDBLonginResult.getResult()){
                case "405":
                  return new AjaxResult(PARAMETERERROR.getCode(),PARAMETERERROR.getMessage());
                case "502":
                  return new AjaxResult(CACHEDBERROR.getCode(),CACHEDBERROR.getMessage());
                case "403":
                  return new AjaxResult(AUTHENTICATIONERROR.getCode(),AUTHENTICATIONERROR.getMessage());
                case "603":
                  return new AjaxResult(ACCOUNTLOCKEDERROR.getCode(),ACCOUNTLOCKEDERROR.getMessage());
                case "402":
                  return new AjaxResult(ACCOUNTERROR.getCode(),ACCOUNTERROR.getMessage());
                case "480":
                    return new AjaxResult(IPLOCKEDERROR.getCode(),IPLOCKEDERROR.getMessage());
                default:
                  return new AjaxResult(Integer.parseInt(cacheDBLonginResult.getResult()),"");
            }
        }
        return AjaxResult.success(loginUser);
    }

    @Override
    public  AjaxResult getBalance(String Username) {
        NCacheDBLink nCacheDBLink=NCacheDBLink.getNCacheDBLink();
        nCacheDBLink.InitEnv();
        nCacheDBLink.ConnectToCacheDB();
        Map<String,Object> map = nCacheDBLink.prc_MMSIFQueryBalance(Username);
        nCacheDBLink.Disconnect();
        nCacheDBLink.Destroy();
        return AjaxResult.success(map);
    }

    @Override
    public  AjaxResult ChangeSwitchAccountPWD(String Username, String OldPass, String NewPass) {
        NCacheDBLink nCacheDBLink=NCacheDBLink.getNCacheDBLink();
        nCacheDBLink.InitEnv();
        nCacheDBLink.ConnectToCacheDB();
        Map<String,Object> map = nCacheDBLink.prc_ChangeSwitchAccountPWD(Username,OldPass,NewPass);
        nCacheDBLink.Disconnect();
        nCacheDBLink.Destroy();
        return AjaxResult.success(map);
    }

}
