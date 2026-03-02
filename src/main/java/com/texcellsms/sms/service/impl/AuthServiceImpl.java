package com.texcellsms.sms.service.impl;

import com.texcellsms.sms.controller.AuthController;
import com.texcellsms.sms.model.VO.CacheDBLonginResult;
import com.texcellsms.sms.model.VO.LoginUser;
import com.texcellsms.sms.service.AuthService;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.AjaxResult;
import org.springframework.stereotype.Service;

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
        return AjaxResult.success(loginUser);
    }
}
