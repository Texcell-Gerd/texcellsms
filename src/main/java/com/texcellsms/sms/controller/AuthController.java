package com.texcellsms.sms.controller;

import com.texcellsms.sms.constant.RequestHeaderConstants;
import com.texcellsms.sms.util.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;



/**
 * 认证控制器：登录/两步认证/修改密码
 */
@RestController
public class AuthController {

    /**
     * 登录接口（无需Authorization，X-Request=login）
     */
    @PostMapping("/api/auth/login")
    public LoginResponse login(
            @RequestHeader(RequestHeaderConstants.X_REQUEST) String xRequest,
            @RequestBody LoginRequest loginRequest,
            HttpServletRequest request) {

        // 1. 验证X-Request是否为login
        if (!"login".equalsIgnoreCase(xRequest)) {
            return LoginResponse.builder()
                    .code(400)
                    .msg("X-Request值必须为login")
                    .build();
        }

        // 2. 模拟CacheDB登录鉴权（后续替换为真实逻辑）
        boolean loginSuccess = "root".equals(loginRequest.getUsername()) && "123456".equals(loginRequest.getPassword());
        if (!loginSuccess) {
            return LoginResponse.builder()
                    .code(401)
                    .msg("用户名或密码错误")
                    .build();
        }

        // 3. 模拟初次登录，返回两步认证二维码
        boolean isFirstLogin = true; // 后续从数据库查询
        if (isFirstLogin) {
            String qrCode = GoogleAuthUtil.generateQrCode(loginRequest.getUsername());
            return LoginResponse.builder()
                    .code(200)
                    .msg("需要两步认证")
                    .needTwoAuth(true)
                    .qrCode(qrCode)
                    .build();
        }

        // 4. 生成Authorization令牌（HMACSHA256加密）
        String token = "auth-token-" + System.currentTimeMillis(); // 后续替换为真实加密逻辑
        return LoginResponse.builder()
                .code(200)
                .msg("登录成功")
                .needTwoAuth(false)
                .token(token)
                .build();
    }


    // 登录请求体
    @Data
    public static class LoginRequest {
        private String username;
        private String password;
    }

    // 登录响应体
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