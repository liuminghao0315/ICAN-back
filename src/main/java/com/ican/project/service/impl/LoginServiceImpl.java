package com.ican.project.service.impl;

import com.ican.project.model.common.Code;
import com.ican.project.model.common.Constants;
import com.ican.project.model.common.Result;
import com.ican.project.model.dto.LoginDTO;
import com.ican.project.model.vo.TokenVO;
import com.ican.project.security.MyUserDetails;
import com.ican.project.service.LoginService;
import com.ican.project.utils.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class LoginServiceImpl implements LoginService {
    private static final Logger logger = LoggerFactory.getLogger(LoginServiceImpl.class);

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private AuthenticationManager authenticationManager;

    @Override
    public Result<?> checkLogin(LoginDTO loginDTO) {
        if (loginDTO == null) {
            logger.error("登录DTO为空");
            return Result.fail(Code.PARAMETER_ERROR, "登录信息不能为空");
        }

        String username = loginDTO.getUsername();
        String password = loginDTO.getPassword();

        if (username == null || username.trim().isEmpty()) {
            logger.warn("用户名为空");
            return Result.authFail("用户名不能为空");
        }

        if (password == null || password.trim().isEmpty()) {
            logger.warn("密码为空: username={}", username);
            return Result.authFail("密码不能为空");
        }

        if (authenticationManager == null) {
            logger.error("认证管理器未初始化");
            return Result.fail(Code.INTERNAL_ERROR, "认证服务未初始化");
        }

        if (redisTemplate == null) {
            logger.error("Redis模板未初始化");
            return Result.fail(Code.INTERNAL_ERROR, "缓存服务未初始化");
        }

        try {
            UsernamePasswordAuthenticationToken authRequest = UsernamePasswordAuthenticationToken.unauthenticated(username, password);
            Authentication authentication = authenticationManager.authenticate(authRequest);
            
            if (authentication != null && authentication.isAuthenticated()) {
                Object principal = authentication.getPrincipal();
                if (!(principal instanceof MyUserDetails)) {
                    logger.error("认证主体类型错误: {}", principal != null ? principal.getClass().getName() : "null");
                    return Result.authFail("认证失败");
                }

                MyUserDetails user = (MyUserDetails) principal;
                if (user.getUser() == null || user.getUser().getId() == null) {
                    logger.error("用户信息不完整");
                    return Result.authFail("用户信息不完整");
                }

                // 生成用户ID Key
                String userIdKey = Constants.RedisKey.USER_ID_PREFIX + user.getUser().getId();
                
                // 生成双Token
                String accessToken = JwtUtil.createAccessToken(userIdKey);
                String refreshToken = JwtUtil.createRefreshToken(userIdKey);
                
                // 将用户信息存储到Redis（使用RefreshToken的有效期）
                redisTemplate.opsForValue().set(
                    userIdKey, 
                    user, 
                    Constants.Token.REFRESH_TOKEN_EXPIRE_DAYS, 
                    TimeUnit.DAYS
                );
                
                // 将RefreshToken存储到Redis（用于验证RefreshToken是否有效）
                String refreshTokenKey = Constants.RedisKey.REFRESH_TOKEN_PREFIX + user.getUser().getId();
                redisTemplate.opsForValue().set(
                    refreshTokenKey, 
                    refreshToken, 
                    Constants.Token.REFRESH_TOKEN_EXPIRE_DAYS, 
                    TimeUnit.DAYS
                );

                // 构建Token响应
                TokenVO tokenVO = TokenVO.builder()
                        .accessToken(accessToken)
                        .refreshToken(refreshToken)
                        .accessTokenExpireTime(JwtUtil.getAccessTokenExpireTime())
                        .refreshTokenExpireTime(JwtUtil.getRefreshTokenExpireTime())
                        .userId(user.getUser().getId().toString())
                        .username(user.getUser().getName())
                        .build();

                logger.info("登录成功，生成双Token: userId={}, username={}", user.getUser().getId(), username);
                return Result.success(tokenVO);
            } else {
                logger.warn("认证未通过: username={}", username);
                return Result.authFail("用户名或密码错误");
            }
        } catch (BadCredentialsException e) {
            logger.warn("认证失败（用户名或密码错误）: username={}", username);
            return Result.authFail("用户名或密码错误");
        } catch (Exception ex) {
            logger.error("登录异常: username={}", username, ex);
            return Result.authFail("登录失败");
        }
    }
}
