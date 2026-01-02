package com.ican.project.service.impl;

import com.ican.project.model.common.Constants;
import com.ican.project.model.common.Result;
import com.ican.project.model.dto.TokenResponse;
import com.ican.project.security.MyUserDetails;
import com.ican.project.service.TokenRefreshService;
import com.ican.project.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class TokenRefreshServiceImpl implements TokenRefreshService {
    private static final Logger logger = LoggerFactory.getLogger(TokenRefreshServiceImpl.class);

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${jwt-access-token-expire-minutes:15}")
    private long accessTokenExpireMinutes;

    @Value("${jwt-refresh-token-expire-days:7}")
    private long refreshTokenExpireDays;

    @Override
    public Result<TokenResponse> refreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.trim().isEmpty()) {
            logger.warn("RefreshToken为空");
            return Result.authFail("refreshToken不能为空");
        }

        try {
            // 解析refreshToken（如果过期会抛出异常）
            Claims claims;
            try {
                claims = JwtUtil.parseToken(refreshToken);
            } catch (Exception e) {
                logger.warn("RefreshToken解析异常: {}", e.getMessage());
                return Result.authFail("refreshToken无效或已过期");
            }
            
            if (claims == null) {
                logger.warn("RefreshToken解析结果为空");
                return Result.authFail("refreshToken无效或已过期");
            }

            String subject = claims.getSubject();
            if (subject == null || !subject.endsWith(":refresh")) {
                logger.warn("RefreshToken格式错误: subject={}", subject);
                return Result.authFail("refreshToken格式错误");
            }

            // 提取userId
            String userId = subject.replace(":refresh", "");
            
            // 验证refreshToken是否在Redis中存在
            String refreshTokenKey = userId + ":refresh";
            Object storedRefreshToken = redisTemplate.opsForValue().get(refreshTokenKey);
            if (storedRefreshToken == null || !refreshToken.equals(storedRefreshToken.toString())) {
                logger.warn("RefreshToken不存在或已失效: userId={}", userId);
                return Result.authFail("refreshToken已失效，请重新登录");
            }

            // 验证用户信息是否还存在
            Object userValue = redisTemplate.opsForValue().get(userId);
            if (userValue == null || !(userValue instanceof MyUserDetails)) {
                logger.warn("用户信息不存在: userId={}", userId);
                return Result.authFail("用户信息已失效，请重新登录");
            }

            MyUserDetails user = (MyUserDetails) userValue;
            
            // 延长用户信息在Redis中的过期时间
            long refreshTokenExpireMinutes = refreshTokenExpireDays * 24 * 60;
            redisTemplate.opsForValue().set(userId, user, refreshTokenExpireMinutes, TimeUnit.MINUTES);

            // 生成新的AccessToken
            long accessTokenExpireMillis = accessTokenExpireMinutes * 60 * 1000;
            String newAccessToken = JwtUtil.createToken(userId, accessTokenExpireMillis);

            // 生成新的RefreshToken（可选：每次刷新都生成新的refreshToken，提高安全性）
            long refreshTokenExpireMillis = refreshTokenExpireDays * 24 * 60 * 60 * 1000;
            String newRefreshToken = JwtUtil.createToken(userId + ":refresh", refreshTokenExpireMillis);
            
            // 更新Redis中的refreshToken
            redisTemplate.opsForValue().set(refreshTokenKey, newRefreshToken, refreshTokenExpireDays, TimeUnit.DAYS);

            TokenResponse tokenResponse = new TokenResponse(newAccessToken, newRefreshToken);
            logger.debug("Token刷新成功: userId={}", userId);
            return Result.success(tokenResponse);
        } catch (Exception e) {
            logger.error("Token刷新异常", e);
            return Result.authFail("token刷新失败");
        }
    }
}

