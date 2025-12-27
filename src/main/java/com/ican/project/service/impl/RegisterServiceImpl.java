package com.ican.project.service.impl;

import com.ican.project.mapper.UserMapper;
import com.ican.project.model.common.Code;
import com.ican.project.model.common.Result;
import com.ican.project.model.dto.RegisterDTO;
import com.ican.project.model.entity.User;
import com.ican.project.service.RegisterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
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

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    //待增强健壮性——对姓名、密码校验（长度、是否为空、特殊符号等等）
    @Override
    public Result<?> checkRegister(RegisterDTO registerDTO) {
        String username = registerDTO.getUsername();
        String password = registerDTO.getPassword();
        String email = registerDTO.getEmail();
        String verifyCode = registerDTO.getVerifyCode();
        List<User> users = userMapper.selectByMap(Map.of("name", username));
        if(!(users==null|| users.isEmpty())){
            return Result.fail(Code.USER_EXISTS,"用户已存在");
        }
        users = userMapper.selectByMap(Map.of("email", email));
        if(!(users==null|| users.isEmpty())){
            return Result.fail(Code.EMAIL_EXISTS,"邮箱已存在");
        }
        String emailByCode;
        try{
            emailByCode = (String) redisTemplate.opsForValue().get("verifyCode:"+verifyCode);
            if(emailByCode==null||!emailByCode.equals(email)){
                return Result.fail(Code.VERIFY_CODE_NOT_EXISTS,"验证码过期或不存在");
            }
        }catch (Exception e){
            e.printStackTrace();
        }
        redisTemplate.delete("verifyCode:"+verifyCode);
        userMapper.insert(new User(username,passwordEncoder.encode(password),email));
        return Result.success(registerDTO);
    }
}
