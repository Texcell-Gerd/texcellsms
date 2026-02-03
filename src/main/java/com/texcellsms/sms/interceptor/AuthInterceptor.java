package com.texcellsms.sms.interceptor;

import com.texcellsms.sms.constant.RequestHeaderConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;


/**
 * 鉴权拦截器：验证X-Request和Authorization请求头
 */
public class AuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 获取X-Request请求头（判断接口类型）
        String xRequest = request.getHeader(RequestHeaderConstants.X_REQUEST);
        if (xRequest == null || xRequest.isEmpty()) {
            response.setStatus(400);
            response.getWriter().write("请求头X-Request不能为空");
            return false;
        }

        // 2. 登录接口无需验证Authorization，其他接口必须验证
        if (!"login".equalsIgnoreCase(xRequest)) {
            String authorization = request.getHeader(RequestHeaderConstants.AUTHORIZATION);
            String requestIp = request.getRemoteAddr();

            if (authorization == null || authorization.isEmpty()) {
                response.setStatus(401);
                response.getWriter().write("Authorization请求头不能为空");
                return false;
            }

            // 3. 验证Authorization和IP（实际需对接CacheDB鉴权逻辑）
            boolean isValid = checkAuth(authorization, requestIp);
            if (!isValid) {
                response.setStatus(403);
                response.getWriter().write("鉴权失败：无效的Authorization或IP");
                return false;
            }
        }
        return true;
    }

    /**
     * 鉴权逻辑（后续对接CacheDB实现）
     */
    private boolean checkAuth(String authorization, String requestIp) {
        // TODO: 替换为实际鉴权逻辑（从CacheDB查询token有效性）
        return true; // 临时返回true，开发阶段跳过真实鉴权
    }
}