package com.texcellsms.sms.controller;

import com.alibaba.fastjson2.JSON;
import com.texcellsms.sms.constant.RequestHeaderConstants;
import com.texcellsms.sms.model.vo.LoginUser;
import com.texcellsms.sms.service.AuthService;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@RestController
public class AuthController {

    @Autowired
    private AuthService authService;

    @PostMapping("/api/auth/getBalance")
    public AjaxResult getBalance(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            HttpServletRequest request, HttpServletResponse response) {
        AjaxResult ajaxResult = authService.getBalance(TokenService.getUsername(authorization));
        String newToken = TokenService.refreshToken(authorization);
        response.setHeader(Constants.HEADER, newToken);
        return ajaxResult;
    }

    @PostMapping("/api/auth/ChangeSwitchAccountPWD")
    public AjaxResult ChangeSwitchAccountPWD(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            HttpServletRequest request, HttpServletResponse response,
            @RequestParam("OldPass") String OldPass,
            @RequestParam("NewPass") String NewPass) {
        AjaxResult ajaxResult = authService.ChangeSwitchAccountPWD(TokenService.getUsername(authorization), OldPass, NewPass);
        String newToken = TokenService.refreshToken(authorization);
        response.setHeader(Constants.HEADER, newToken);
        return ajaxResult;
    }

    @GetMapping("/api/auth/getRate")
    public AjaxResult getRate(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            @RequestParam("MCC") String MCC,
            @RequestParam("MNC") String MNC,
            HttpServletRequest request, HttpServletResponse response) {
        AjaxResult ajaxResult = authService.getRate(TokenService.getUsername(authorization), MCC, MNC);
        String newToken = TokenService.refreshToken(authorization);
        response.setHeader(Constants.HEADER, newToken);
        return ajaxResult;
    }

    @PostMapping("/api/auth/login")
    public AjaxResult login(
            @RequestHeader(RequestHeaderConstants.X_REQUEST) String xRequest,
            @RequestHeader("AppName") String AppName,
            @RequestHeader("Secret") String Secret,
            HttpServletRequest request, HttpServletResponse response) {
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername(AppName);
        loginRequest.setPassword(Secret);
        String IP = TokenService.getIpAddress(request);
        AjaxResult ajaxResult = authService.login(loginRequest, IP);
        if (ajaxResult.get("data") != null && !ajaxResult.get("data").equals("")) {
            response.setHeader(Constants.HEADER, JSON.toJavaObject(ajaxResult.get("data"), LoginUser.class).getToken());
        }
        return ajaxResult;
    }

    @Data
    public static class LoginRequest {
        private String username;
        private String password;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginResponse {
        private int code;
        private String msg;
        private boolean needTwoAuth;
        private String qrCode;
        private String token;
    }
}