package com.texcellsms.sms.service;


import com.texcellsms.sms.controller.AuthController;
import com.texcellsms.sms.util.AjaxResult;
import org.springframework.stereotype.Service;

@Service
public interface AuthService {
  AjaxResult login(AuthController.LoginRequest loginRequest,String IP);

  AjaxResult getBalance(String Username);

  AjaxResult ChangeSwitchAccountPWD(String Username,String OldPass,String NewPass);
}
