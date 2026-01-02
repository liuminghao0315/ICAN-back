package com.ican.project.service.impl;

import com.ican.project.model.common.Constants;
import com.ican.project.model.common.Result;
import com.ican.project.model.dto.LoginDTO;
import com.ican.project.model.dto.TokenResponse;
import com.ican.project.security.MyUserDetails;
import com.ican.project.service.LoginService;
import com.ican.project.utils.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${jwt-expire-minutes}")
    private long expire;

    // AccessToken有效期（分钟），默认15分钟
    @Value("${jwt-access-token-expire-minutes:15}")
    private long accessTokenExpireMinutes;

    // RefreshToken有效期（天），默认7天
    @Value("${jwt-refresh-token-expire-days:7}")
    private long refreshTokenExpireDays;

    @Override
    public Result<?> checkLogin(LoginDTO loginDTO) {
        if (loginDTO == null) {
            logger.error("登录DTO为空");
            return Result.fail(com.ican.project.model.common.Code.PARAMETER_ERROR, "登录信息不能为空");
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
            return Result.fail(com.ican.project.model.common.Code.INTERNAL_ERROR, "认证服务未初始化");
        }

        if (redisTemplate == null) {
            logger.error("Redis模板未初始化");
            return Result.fail(com.ican.project.model.common.Code.INTERNAL_ERROR, "缓存服务未初始化");
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

                String userId = Constants.RedisKey.USER_ID_PREFIX + user.getUser().getId();
                
                // 存储用户信息到Redis，使用refreshToken的过期时间
                long refreshTokenExpireMinutes = refreshTokenExpireDays * 24 * 60;
                redisTemplate.opsForValue().set(userId, user, refreshTokenExpireMinutes, TimeUnit.MINUTES);
                
                // 生成AccessToken（短期有效）
                long accessTokenExpireMillis = accessTokenExpireMinutes * 60 * 1000;
                String accessToken = JwtUtil.createToken(userId, accessTokenExpireMillis);
                
                // 生成RefreshToken（长期有效）
                long refreshTokenExpireMillis = refreshTokenExpireDays * 24 * 60 * 60 * 1000;
                String refreshToken = JwtUtil.createToken(userId + ":refresh", refreshTokenExpireMillis);
                
                // 将refreshToken存储到Redis，用于验证刷新请求
                String refreshTokenKey = Constants.RedisKey.USER_ID_PREFIX + user.getUser().getId() + ":refresh";
                redisTemplate.opsForValue().set(refreshTokenKey, refreshToken, refreshTokenExpireDays, TimeUnit.DAYS);
                
                TokenResponse tokenResponse = new TokenResponse(accessToken, refreshToken);
                logger.debug("登录成功，生成双Token: userId={}, accessTokenExpire={}分钟, refreshTokenExpire={}天", 
                        userId, accessTokenExpireMinutes, refreshTokenExpireDays);
                return Result.success(tokenResponse);
            } else {
                logger.warn("认证未通过: username={}", username);
                return Result.authFail("用户名或密码错误");
            }
        } catch (BadCredentialsException e) {
            logger.warn("认证失败（用户名或密码错误）: username={}", username);
            // 保持原有逻辑：直接返回认证失败，不抛出异常
            return Result.authFail("用户名或密码错误");
        } catch (Exception ex) {
            logger.error("登录异常: username={}", username, ex);
            // 保持原有逻辑：捕获异常并返回认证失败
            return Result.authFail("登录失败");
        }
    }
}
