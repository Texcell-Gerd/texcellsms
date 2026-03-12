package com.texcellsms.sms.interceptor;


import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;


public class AuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // ========== 注释掉所有鉴权逻辑 ==========
        // 1. 注释Authorization校验
        // String token = request.getHeader("Authorization");
        // if (token == null || !token.startsWith("Bearer ")) {
        //     response.setStatus(401);
        //     response.getWriter().write("Authorization token is required");
        //     return false;
        // }

        // 2. 注释token解析/验证逻辑（如果有）
        // try {
        //     String realToken = token.substring(7);
        //     // 验证token的代码...
        // } catch (Exception e) {
        //     response.setStatus(401);
        //     response.getWriter().write("Invalid token");
        //     return false;
        // }

        // ========== 保留X-Request校验（可选，也可以注释） ==========
        // 如果连X-Request也不想传，把下面这段也注释掉
        String xRequest = request.getHeader("X-Request");
        if (xRequest == null || xRequest.trim().isEmpty()) {
            response.setStatus(400);
            response.getWriter().write("X-Request header is required");
            return false;
        }
        //登录接口放行
        if (xRequest.equals("Login")){
            return true;
        }
        // 1. 注释Authorization校验
         String token = request.getHeader("Authorization");
         if (token == null || token.trim().isEmpty()) {
             response.setStatus(401);
             response.getWriter().write("Authorization token is required");
             return false;
         }
         //验证令牌
         HttpSession session = request.getSession();
         String authorization = (String) session.getServletContext().getAttribute(token);
         //验证通过放行
         if (authorization != null&&!authorization.trim().equals("")){
             return true;
         }
        //直接返回true，允许所有请求通过
        response.setStatus(401);
        response.getWriter().write("Authorization token is error");
        return false;
    }
}