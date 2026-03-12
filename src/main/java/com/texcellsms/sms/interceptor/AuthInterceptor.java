package com.texcellsms.sms.interceptor;


import com.alibaba.fastjson2.JSON;
import com.texcellsms.sms.service.EccomSubmitRecordService;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.Constants;
import com.texcellsms.sms.util.SMSResponseMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;


public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(AuthInterceptor.class);

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
        // 获取请求地址
        String url = request.getRequestURI();
        if (url == null || url.trim().isEmpty()) {
            setUnauthorizedResponse(request,response,SMSResponseMessage.REQUESTERROR);
            return false;
        }
        //登录接口放行
        if (url.equals(Constants.LOGIN)){
            return true;
        }
        // 1. 注释Authorization校验
         String token = request.getHeader(Constants.HEADER);
         if (token == null || token.trim().isEmpty()) {
             setUnauthorizedResponse(request,response,SMSResponseMessage.AUTHORIZATIONERROR);
             return false;
         }
         //验证令牌
         HttpSession session = request.getSession();
         Map<String,String> map = (Map<String, String>) session.getServletContext().getAttribute(token);
         //map 为空
         if (map == null|| map.isEmpty()){
             setUnauthorizedResponse(request,response,SMSResponseMessage.AUTHORIZATIONTIMEOUT);
             return false;
         }
         String authorization = map.get(Constants.HEADER);
         //验证通不通过
         if (authorization == null||authorization.trim().equals("")){
             setUnauthorizedResponse(request,response,SMSResponseMessage.AUTHORIZATIONTIMEOUT);
             return false;
         }
         //IP 发生变化
         if (!TokenService.getIpAddress(request).equals(map.get("IP"))){
             setUnauthorizedResponse(request,response,SMSResponseMessage.IPAUTHORIZATIONERROR);
             return false;
         }
        //验证通过放行
        return true;
    }

    private void setUnauthorizedResponse(HttpServletRequest request, HttpServletResponse response, SMSResponseMessage smsResponseMessage)
    {
        try
        {
            response.setContentType("application/json");
            response.setStatus(Constants.FAIL);
            logger.error("[鉴权异常处理]请求路径:{}", request.getRequestURI());
            response.setCharacterEncoding(Constants.UTF8);
            response.getWriter().print(JSON.toJSON(smsResponseMessage).toString());
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}