package com.ican.project.service.impl;

import com.ican.project.model.common.Constants;
import com.ican.project.model.common.Result;
import com.ican.project.security.MyUserDetails;
import com.ican.project.service.LogoutService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class logoutServiceImpl implements LogoutService {
    private static final Logger logger = LoggerFactory.getLogger(logoutServiceImpl.class);

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public Result<?> logout() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null) {
                logger.warn("当前未登录，无需登出");
                return Result.success("登出成功");
            }

            Object principal = authentication.getPrincipal();
            if (principal == null) {
                logger.warn("认证主体为空");
                SecurityContextHolder.clearContext();
                return Result.success("登出成功");
            }

            if (!(principal instanceof MyUserDetails)) {
                logger.warn("认证主体类型错误: {}", principal.getClass().getName());
                SecurityContextHolder.clearContext();
                return Result.success("登出成功");
            }

            MyUserDetails user = (MyUserDetails) principal;
            if (user.getUser() == null || user.getUser().getId() == null) {
                logger.warn("用户信息不完整");
                SecurityContextHolder.clearContext();
                return Result.success("登出成功");
            }

            String redisKey = Constants.RedisKey.USER_ID_PREFIX + user.getUser().getId();

            if (redisTemplate == null) {
                logger.error("Redis模板未初始化");
                SecurityContextHolder.clearContext();
                return Result.success("登出成功");
            }

            try {
                redisTemplate.delete(redisKey);
                logger.debug("Redis中的用户信息已删除: redisKey={}", redisKey);
            } catch (Exception e) {
                logger.warn("删除Redis中的用户信息失败: redisKey={}", redisKey, e);
                // 继续执行登出流程
            }

            SecurityContextHolder.clearContext();
            logger.info("用户登出成功: userId={}", user.getUser().getId());
            return Result.success("登出成功");
        } catch (Exception e) {
            logger.error("登出异常", e);
            // 即使出现异常，也清除上下文
            try {
                SecurityContextHolder.clearContext();
            } catch (Exception ex) {
                logger.error("清除安全上下文异常", ex);
            }
            throw e; // 让全局异常处理器处理
        }
    }
}
