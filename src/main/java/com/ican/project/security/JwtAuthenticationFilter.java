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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    @Autowired
    private RedisTemplate<String,Object> redisTemplate;

    @Autowired
    private NotFilterPaths notFilterPaths;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request){
        String path = request.getServletPath();
        for (String skipPath : notFilterPaths.accuratePaths) {
            if(skipPath.equals(path)){
                return true;
            }
        }
        for (String skipPath : notFilterPaths.startWithPathsInFilter) {
            if(path.startsWith(skipPath)){
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if(header==null || !header.startsWith("Bearer ")) {
            ResponseUtil.write(response, Result.authFail("accessToken缺失"), Code.AUTH_FAILURE);
            return;
        }
        String accessToken = header.replace("Bearer ", "");
        String userId;
        try{
            userId = JwtUtil.parseToken(accessToken).getSubject();
        }catch (Exception e) {
            ResponseUtil.write(response, Result.authFail("accessToken错误或过期"),Code.AUTH_FAILURE);
            return;
        }
        MyUserDetails user = (MyUserDetails) redisTemplate.opsForValue().get(userId);
        if(user == null) {
            ResponseUtil.write(response, Result.authFail("redis读取不到accessToken"),Code.AUTH_FAILURE);
            return;
        }
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }
}
