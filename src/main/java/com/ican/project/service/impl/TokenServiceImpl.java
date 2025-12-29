package com.ican.project.service.impl;

import com.ican.project.model.common.Code;
import com.ican.project.model.common.Constants;
import com.ican.project.model.common.Result;
import com.ican.project.model.vo.TokenVO;
import com.ican.project.security.MyUserDetails;
import com.ican.project.service.TokenService;
import com.ican.project.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Token服务实现
 * 处理Token刷新逻辑
 */
@Service
public class TokenServiceImpl implements TokenService {
    private static final Logger logger = LoggerFactory.getLogger(TokenServiceImpl.class);

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public Result<TokenVO> refreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.trim().isEmpty()) {
            logger.warn("RefreshToken为空");
            return Result.fail(Code.PARAMETER_ERROR, "refreshToken不能为空");
        }

        try {
            // 解析RefreshToken
            Claims claims = JwtUtil.parseToken(refreshToken);
            
            // 验证是否为RefreshToken类型
            if (!JwtUtil.isRefreshToken(claims)) {
                logger.warn("Token类型错误，需要RefreshToken");
                return Result.fail(Code.PARAMETER_ERROR, "请使用refreshToken进行刷新");
            }

            String userIdKey = claims.getSubject();
            if (userIdKey == null || userIdKey.trim().isEmpty()) {
                logger.warn("RefreshToken中用户ID为空");
                return Result.fail(Code.AUTH_FAILURE, "refreshToken无效");
            }

            // 从Redis验证RefreshToken是否有效
            String userId = userIdKey.replace(Constants.RedisKey.USER_ID_PREFIX, "");
            String refreshTokenKey = Constants.RedisKey.REFRESH_TOKEN_PREFIX + userId;
            Object storedToken = redisTemplate.opsForValue().get(refreshTokenKey);
            
            if (storedToken == null || !refreshToken.equals(storedToken.toString())) {
                logger.warn("RefreshToken不匹配或已失效: userId={}", userId);
                return Result.fail(Code.REFRESH_TOKEN_EXPIRED, "refreshToken已失效，请重新登录");
            }

            // 从Redis获取用户信息
            Object userObj = redisTemplate.opsForValue().get(userIdKey);
            if (userObj == null) {
                logger.warn("用户信息不存在: userId={}", userId);
                return Result.fail(Code.REFRESH_TOKEN_EXPIRED, "登录已过期，请重新登录");
            }

            if (!(userObj instanceof MyUserDetails)) {
                logger.error("用户信息类型错误: userId={}", userId);
                return Result.fail(Code.AUTH_FAILURE, "用户信息异常");
            }

            MyUserDetails user = (MyUserDetails) userObj;

            // 生成新的Token对
            String newAccessToken = JwtUtil.createAccessToken(userIdKey);
            String newRefreshToken = JwtUtil.createRefreshToken(userIdKey);

            // 更新Redis中的RefreshToken
            redisTemplate.opsForValue().set(
                refreshTokenKey,
                newRefreshToken,
                Constants.Token.REFRESH_TOKEN_EXPIRE_DAYS,
                TimeUnit.DAYS
            );

            // 延长用户信息的过期时间
            redisTemplate.expire(userIdKey, Constants.Token.REFRESH_TOKEN_EXPIRE_DAYS, TimeUnit.DAYS);

            // 构建响应
            TokenVO tokenVO = TokenVO.builder()
                    .accessToken(newAccessToken)
                    .refreshToken(newRefreshToken)
                    .accessTokenExpireTime(JwtUtil.getAccessTokenExpireTime())
                    .refreshTokenExpireTime(JwtUtil.getRefreshTokenExpireTime())
                    .userId(userId)
                    .username(user.getUser().getName())
                    .build();

            logger.info("Token刷新成功: userId={}", userId);
            return Result.success(tokenVO);

        } catch (ExpiredJwtException e) {
            // RefreshToken已过期
            logger.info("RefreshToken已过期");
            return Result.fail(Code.REFRESH_TOKEN_EXPIRED, "refreshToken已过期，请重新登录");
        } catch (Exception e) {
            logger.error("Token刷新异常", e);
            return Result.fail(Code.AUTH_FAILURE, "Token刷新失败");
        }
    }

    @Override
    public Result<String> logout(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return Result.fail(Code.PARAMETER_ERROR, "用户ID不能为空");
        }

        try {
            // 删除用户信息
            String userIdKey = Constants.RedisKey.USER_ID_PREFIX + userId;
            redisTemplate.delete(userIdKey);

            // 删除RefreshToken
            String refreshTokenKey = Constants.RedisKey.REFRESH_TOKEN_PREFIX + userId;
            redisTemplate.delete(refreshTokenKey);

            logger.info("用户登出成功: userId={}", userId);
            return Result.success("登出成功");
        } catch (Exception e) {
            logger.error("登出异常: userId={}", userId, e);
            return Result.fail(Code.INTERNAL_ERROR, "登出失败");
        }
    }
}

