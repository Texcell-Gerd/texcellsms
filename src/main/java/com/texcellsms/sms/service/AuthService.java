package com.texcellsms.sms.service;


import com.texcellsms.sms.controller.AuthController;
import com.texcellsms.sms.util.AjaxResult;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

@Service
public interface AuthService {
  AjaxResult login(AuthController.LoginRequest loginRequest,String IP);

  AjaxResult login2FA(HttpSession session,AuthController.LoginRequest loginRequest,String IP,HttpServletRequest request);

  AjaxResult AVerify(String Token,String code,String IP);

  AjaxResult AVerifyEmail(HttpSession session, String Token, String code, String IP);

  AjaxResult getBalance(String Username);

  AjaxResult getRate(String Username,String MCC,String MNC);

  AjaxResult ChangeSwitchAccountPWD(String Username,String OldPass,String NewPass);

  AjaxResult Update2FAVerify(String name,String key,String Enable,String Email,String OpType);
}
