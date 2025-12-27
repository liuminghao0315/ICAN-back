package com.ican.project.service.impl;

import com.ican.project.model.common.Result;
import com.ican.project.security.MyUserDetails;
import com.ican.project.service.LogoutService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class logoutServiceImpl implements LogoutService {
    @Autowired
    private RedisTemplate<String,Object> redisTemplate;

    @Override
    public Result<?> logout() {
        MyUserDetails user = (MyUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String redisKey = "userId:"+user.getUser().getId();
        redisTemplate.delete(redisKey);
        SecurityContextHolder.clearContext();
        return Result.success("登出成功");
    }
}
