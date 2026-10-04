package com.factcheck.interceptor;

import com.factcheck.constant.JwtClaimsConstant;
import com.factcheck.context.BaseContext;
import com.factcheck.properties.JwtProperties;
import com.factcheck.util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 用户端 JWT 拦截器，校验用户 token 并注入当前用户 ID
 */
@Component
@Slf4j
public class JwtTokenUserInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtProperties jwtProperties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        String token = request.getHeader(jwtProperties.getUserTokenName());
        try {
            Claims claims = JwtUtil.parseJWT(jwtProperties.getUserSecretKey(), token);
            Long userId = Long.valueOf(claims.get(JwtClaimsConstant.USER_ID).toString());
            BaseContext.setCurrentId(userId);
            log.info("用户端 JWT 校验通过，userId={}", userId);
            return true;
        } catch (Exception ex) {
            log.error("用户端 JWT 校验失败：{}", ex.getMessage());
            response.setStatus(401);
            return false;
        }
    }

    /**
     * 请求结束后清理 ThreadLocal（P5-48）
     *
     * <p>Tomcat 的工作线程是复用的：不清理的话，上一个请求的用户 ID 会残留在线程上，
     * 一旦某个请求没经过拦截器却读到了 {@code BaseContext.getCurrentId()}，
     * 就会拿到**别人的身份**——这是典型的越权隐患，也顺带造成内存泄漏。</p>
     *
     * <p>无论请求成功、抛异常还是被拒绝（preHandle 返回 false 也会回调 afterCompletion）都会执行。</p>
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        BaseContext.removeCurrentId();
    }
}
