package com.texcellsms.sms.service.impl;

import CacheDB.NCacheDBLink;
import com.texcellsms.sms.controller.AuthController;
import com.texcellsms.sms.model.vo.CacheDBLonginResult;
import com.texcellsms.sms.model.vo.LoginUser;
import com.texcellsms.sms.service.AuthService;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.AjaxResult;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AuthServiceImpl implements AuthService {

    @Override
    public AjaxResult login(AuthController.LoginRequest loginRequest) {
        TokenService tokenService=new TokenService();
        LoginUser loginUser=new LoginUser();
        loginUser.setUsername(loginRequest.getUsername());
        loginUser.setPassword(loginRequest.getPassword());
        loginUser.setIP(TokenService.getIpAddress());
        CacheDBLonginResult cacheDBLonginResult = tokenService.getToken(loginUser);
        loginUser.setToken(cacheDBLonginResult.getFAKey());
        tokenService.createToken(loginUser);
        if (!cacheDBLonginResult.getResult().equals("0")){
            return new AjaxResult(Integer.parseInt(cacheDBLonginResult.getResult()),"");
        }
        return AjaxResult.success(loginUser);
    }

    @Override
    public AjaxResult getBalance() {
        NCacheDBLink nCacheDBLink=NCacheDBLink.getNCacheDBLink();
        nCacheDBLink.InitEnv();
        nCacheDBLink.ConnectToCacheDB();
        Map<String,Object> map = nCacheDBLink.prc_MMSIFQueryBalance(TokenService.getUsername());
        nCacheDBLink.Disconnect();
        return AjaxResult.success(map);
    }
}
