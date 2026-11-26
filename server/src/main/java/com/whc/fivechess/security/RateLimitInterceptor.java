package com.whc.fivechess.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 普通 HTTP 接口按 IP 的总速率限制：超过阈值直接返回 429，不进入控制器。
 * 跨域预检请求（OPTIONS）不计入限流。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    @Autowired
    private AbuseGuard abuseGuard;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String ip = ClientIpResolver.resolve(request);
        if (abuseGuard.allowHttp(ip)) {
            return true;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":429,\"success\":false,\"message\":\"请求过于频繁，请稍后再试！\"}");
        return false;
    }
}