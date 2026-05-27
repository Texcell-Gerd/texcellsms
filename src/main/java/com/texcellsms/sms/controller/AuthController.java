package com.texcellsms.sms.controller;

import com.alibaba.fastjson2.JSON;
import com.texcellsms.sms.constant.RequestHeaderConstants;
import com.texcellsms.sms.model.vo.LoginUser;
import com.texcellsms.sms.service.AuthService;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.*;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

@RestController
public class AuthController {

    @Autowired
    private AuthService authService;

    /**
     * 获取当前账户余额
     */
    @PostMapping("/api/auth/getBalance")
    public AjaxResult getBalance(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            HttpServletRequest request, HttpServletResponse response) {
        AjaxResult ajaxResult = authService.getBalance(TokenService.getUsername(authorization));
        String newToken = TokenService.refreshToken(authorization);
        response.setHeader(Constants.HEADER, newToken);
        return ajaxResult;
    }

    /**
     * 修改子账户密码
     */
    @PostMapping("/api/auth/ChangeSwitchAccountPWD")
    public AjaxResult ChangeSwitchAccountPWD(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            HttpServletRequest request, HttpServletResponse response,
            @RequestParam("OldPass") String OldPass,
            @RequestParam("NewPass") String NewPass) {
        AjaxResult ajaxResult = authService.ChangeSwitchAccountPWD(TokenService.getUsername(authorization), OldPass, NewPass);
        TokenService.deleteToken(authorization);
        return ajaxResult;
    }

    /**
     * 根据MCC+MNC查询费率
     */
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

    /**
     * 账户登录接口
     */
    @PostMapping("/api/auth/login")
    public AjaxResult login(
            @RequestHeader(RequestHeaderConstants.X_REQUEST) String xRequest,
            @RequestHeader("AppName") String AppName,
            @RequestHeader("Secret") String Secret,
            HttpServletRequest request, HttpServletResponse response,HttpSession session) {
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername(AppName);
        loginRequest.setPassword(Secret);
        String IP = TokenService.getIpAddress(request);
        AjaxResult ajaxResult = authService.login2FA(session,loginRequest,IP,request);
        if (ajaxResult.get("data") != null && !ajaxResult.get("data").equals("")) {
            response.setHeader(Constants.HEADER, JSON.toJavaObject(ajaxResult.get("data"), LoginUser.class).getToken());
        }
        return ajaxResult;
    }



    @PostMapping("/api/auth/2FAVerify")
    public AjaxResult AVerify(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String Authorization,
            @RequestHeader("Code") String code,
            HttpServletRequest request, HttpServletResponse response) {
        String IP = TokenService.getIpAddress(request);
        AjaxResult ajaxResult = authService.AVerify(Authorization,code,IP);
        if (ajaxResult.get("data") != null && !ajaxResult.get("data").equals("")) {
            response.setHeader(Constants.HEADER, JSON.toJavaObject(ajaxResult.get("data"), LoginUser.class).getToken());
        }
        return ajaxResult;
    }


    @PostMapping("/api/auth/Replace2FAVerify")
    public AjaxResult ReplaceAVerify(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String Authorization,
            @RequestHeader("Key") String key,
            @RequestHeader("Enable") String Enable,
            @RequestHeader("Email") String Email,
            @RequestHeader("OpType") String OpType,
            HttpServletRequest request, HttpServletResponse response) {
        AjaxResult ajaxResult = authService.Update2FAVerify(TokenService.getUsername(Authorization),key,Enable,Email,OpType);
        if (ajaxResult.get("data") != null && !ajaxResult.get("data").equals("")) {
            response.setHeader(Constants.HEADER, JSON.toJavaObject(ajaxResult.get("data"), LoginUser.class).getToken());
        }
        return ajaxResult;
    }

    @PostMapping("/api/auth/2FAVerifyEmail")
    public AjaxResult AVerifyEmail(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String Authorization,
            @RequestHeader("Code") String code,
            HttpServletRequest request, HttpServletResponse response, HttpSession session) {
        String IP = TokenService.getIpAddress(request);
        AjaxResult ajaxResult = authService.AVerifyEmail(session,Authorization,code,IP);
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
}