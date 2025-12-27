package com.ican.project.service.impl;

import com.ican.project.model.common.Result;
import com.ican.project.model.dto.LoginDTO;
import com.ican.project.security.MyUserDetails;
import com.ican.project.service.LoginService;
import com.ican.project.utils.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class LoginServiceImpl implements LoginService {
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private AuthenticationManager authenticationManager;

//    @Value("${jwt-expire-minutes}")
    private long expire = 5;

    @Override
    public Result<?> checkLogin(LoginDTO loginDTO) {
        String username = loginDTO.getUsername();
        String password = loginDTO.getPassword();
        UsernamePasswordAuthenticationToken authRequest = UsernamePasswordAuthenticationToken.unauthenticated(username, password);
        try{
            Authentication authentication = authenticationManager.authenticate(authRequest);
            if(authentication.isAuthenticated()){
                MyUserDetails user = (MyUserDetails) authentication.getPrincipal();
                String userId = "userId:"+user.getUser().getId();
                redisTemplate.opsForValue().set(userId, user, expire, TimeUnit.MINUTES);
                String token = JwtUtil.createToken(userId, 1000 * 60 * expire);
                return Result.success(token);
            }
        }catch (Exception ex){
            return Result.authFail();
        }
        return Result.authFail();
    }
}
