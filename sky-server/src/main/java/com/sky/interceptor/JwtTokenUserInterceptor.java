package com.sky.interceptor;

import com.sky.constant.JwtClaimsConstant;
import com.sky.context.BaseContext;
import com.sky.properties.JwtProperties;
import com.sky.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * jwt令牌校验的拦截器
 */
@Component
@Slf4j
public class JwtTokenUserInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtProperties jwtProperties;

    /**
     * 校验jwt
     *
     * @param request
     * @param response
     * @param handler
     * @return
     * @throws Exception
     */
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {

        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        String token = request.getHeader(jwtProperties.getUserTokenName());

        try {
            log.info("jwt校验:{}", token);

            Claims claims = JwtUtil.parseJWT(jwtProperties.getUserSecretKey(), token);

            Object obj = claims.get(JwtClaimsConstant.USER_ID);
            Long userId = obj == null ? null : Long.valueOf(obj.toString());

            log.info("当前用户的id：{}", userId);

            if (userId == null) {
                throw new RuntimeException("userId为空");
            }

            BaseContext.setCurrentId(userId);

            return true;

        } catch (Exception ex) {
            log.error("JWT校验失败", ex);
            response.setStatus(401);
            return false;
        }
    }
}
