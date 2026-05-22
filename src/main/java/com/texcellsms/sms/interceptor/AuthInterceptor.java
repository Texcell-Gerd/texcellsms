package com.texcellsms.sms.interceptor;

import com.alibaba.fastjson2.JSON;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.Constants;
import com.texcellsms.sms.util.SMSResponseMessage;
import com.texcellsms.sms.util.AjaxResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;

public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(AuthInterceptor.class);

    // 过期时间从常量读取，你随时改
    private static final long EXPIRE_TIME = Constants.TOKEN_EXPIRE * 60 * 1000L;


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (request.getRequestURI().equals(Constants.LOGIN)) {
            return true;
        }

        if (request.getRequestURI().equals(Constants.Verify)) {
            return true;
        }

        // 1. 取 Token（兼容大小写）
        String token = request.getHeader(Constants.HEADER);
        if (token == null || token.trim().isEmpty()) {
            token = request.getHeader("authorization");
        }

        // 2. 空值过滤
        if (token == null || token.trim().isEmpty() || "undefined".equals(token)) {
            sendError(response, SMSResponseMessage.AUTHORIZATIONERROR);
            return false;
        }

        // 3. 检查是否存在
        HttpSession session = request.getSession();
        Map<String, String> tokenMap = (Map<String, String>) session.getServletContext().getAttribute(token);
        if (tokenMap == null) {
            sendError(response, SMSResponseMessage.AUTHORIZATIONTIMEOUT);
            return false;
        }

        // 4. IP 绑定
        String loginIp = tokenMap.get("IP");
        String currentIp = TokenService.getIpAddress(request);
        if (loginIp == null || !loginIp.equals(currentIp)) {
            sendError(response, SMSResponseMessage.IPAUTHORIZATIONERROR);
            return false;
        }

        // 5. 过期校验
        String createTimeStr = tokenMap.get("createTime");
        long now = System.currentTimeMillis();
        if (createTimeStr == null || (now - Long.parseLong(createTimeStr)) > EXPIRE_TIME) {
            session.getServletContext().removeAttribute(token);
            sendError(response, SMSResponseMessage.AUTHORIZATIONTIMEOUT);
            return false;
        }

        return true;
    }

    private void sendError(HttpServletResponse response, SMSResponseMessage msg) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        AjaxResult result = AjaxResult.error(msg.getCode(), msg.getMessage());
        response.getWriter().write(JSON.toJSONString(result));
    }
}