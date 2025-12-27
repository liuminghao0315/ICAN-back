package com.ican.project.service.impl;

import com.ican.project.mapper.UserMapper;
import com.ican.project.model.common.Code;
import com.ican.project.model.common.Result;
import com.ican.project.model.dto.LoginDTO;
import com.ican.project.model.dto.RegisterDTO;
import com.ican.project.model.entity.User;
import com.ican.project.security.MyUserDetails;
import com.ican.project.service.RegisterService;
import com.ican.project.utils.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class RegisterServiceImpl implements RegisterService {
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserMapper userMapper;

    @Override
    public Result<?> checkRegister(RegisterDTO registerDTO) {
        String username = registerDTO.getUsername();
        String password = registerDTO.getPassword();
        String email = registerDTO.getEmail();
        List<User> users = userMapper.selectByMap(Map.of("name", username));
        if(!(users==null|| users.isEmpty())){
            return Result.fail(Code.USER_EXISTS,"用户已存在");
        }
        users = userMapper.selectByMap(Map.of("email", email));
        if(!(users==null|| users.isEmpty())){
            return Result.fail(Code.EMAIL_EXISTS,"邮箱已存在");
        }
        userMapper.insert(new User(username,passwordEncoder.encode(password),email));
        return Result.success(registerDTO);
    }
}
