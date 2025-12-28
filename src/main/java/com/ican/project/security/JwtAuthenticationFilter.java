package com.ican.project.security;

import com.ican.project.model.common.Code;
import com.ican.project.model.common.NotFilterPaths;
import com.ican.project.model.common.Result;
import com.ican.project.utils.JwtUtil;
import com.ican.project.utils.ResponseUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private NotFilterPaths notFilterPaths;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (request == null) {
            return false;
        }

        if (notFilterPaths == null) {
            logger.error("NotFilterPaths未初始化");
            return false;
        }

        String path = request.getServletPath();
        if (path == null) {
            return false;
        }

        try {
            if (notFilterPaths.accuratePaths != null) {
                for (String skipPath : notFilterPaths.accuratePaths) {
                    if (skipPath != null && skipPath.equals(path)) {
                        return true;
                    }
                }
            }

            if (notFilterPaths.startWithPathsInFilter != null) {
                for (String skipPath : notFilterPaths.startWithPathsInFilter) {
                    if (skipPath != null && path.startsWith(skipPath)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("路径过滤检查异常: path={}", path, e);
        }

        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if (request == null || response == null || filterChain == null) {
            logger.error("请求参数为空");
            return;
        }

        if (redisTemplate == null) {
            logger.error("Redis模板未初始化");
            ResponseUtil.write(response, Result.authFail("系统服务未初始化"), Code.AUTH_FAILURE);
            return;
        }

        String path = request.getServletPath();
        logger.debug("JWT过滤器处理请求: path={}", path);

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            logger.warn("AccessToken缺失: path={}", path);
            ResponseUtil.write(response, Result.authFail("accessToken缺失"), Code.AUTH_FAILURE);
            return;
        }

        String accessToken = header.replace("Bearer ", "");
        if (accessToken == null || accessToken.trim().isEmpty()) {
            logger.warn("AccessToken为空: path={}", path);
            ResponseUtil.write(response, Result.authFail("accessToken为空"), Code.AUTH_FAILURE);
            return;
        }

        String userId;
        try {
            var claims = JwtUtil.parseToken(accessToken);
            if (claims == null) {
                logger.warn("Token解析结果为空: path={}", path);
                ResponseUtil.write(response, Result.authFail("accessToken错误或过期"), Code.AUTH_FAILURE);
                return;
            }

            userId = claims.getSubject();
            if (userId == null || userId.trim().isEmpty()) {
                logger.warn("Token中用户ID为空: path={}", path);
                ResponseUtil.write(response, Result.authFail("accessToken错误或过期"), Code.AUTH_FAILURE);
                return;
            }
        } catch (Exception e) {
            logger.warn("Token解析异常: path={}, error={}", path, e.getMessage());
            ResponseUtil.write(response, Result.authFail("accessToken错误或过期"), Code.AUTH_FAILURE);
            return;
        }

        try {
            Object value = redisTemplate.opsForValue().get(userId);
            if (value == null) {
                logger.warn("Redis中读取不到用户信息: userId={}, path={}", userId, path);
                ResponseUtil.write(response, Result.authFail("登录已过期，请重新登录"), Code.AUTH_FAILURE);
                return;
            }

            if (!(value instanceof MyUserDetails)) {
                logger.error("Redis中用户信息类型错误: userId={}, type={}", userId, value.getClass().getName());
                ResponseUtil.write(response, Result.authFail("用户信息异常"), Code.AUTH_FAILURE);
                return;
            }

            MyUserDetails user = (MyUserDetails) value;
            if (user.getAuthorities() == null) {
                logger.warn("用户权限为空: userId={}", userId);
            }

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            logger.debug("JWT认证成功: userId={}, path={}", userId, path);
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            logger.error("JWT过滤器处理异常: userId={}, path={}", userId, path, e);
            ResponseUtil.write(response, Result.authFail("认证处理异常"), Code.AUTH_FAILURE);
        }
    }
}
