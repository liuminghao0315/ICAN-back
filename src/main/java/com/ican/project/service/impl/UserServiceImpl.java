package com.ican.project.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ican.project.mapper.UserMapper;
import com.ican.project.model.common.Code;
import com.ican.project.model.common.Result;
import com.ican.project.model.entity.User;
import com.ican.project.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public List<String> getUserPermissions(String userId) {
        return userMapper.selectPermsByUserId(userId);
    }

    @Override
    public User getUserByUsername(String username) {
        List<User> users = userMapper.selectByMap(Map.of("name", username));
        if(users != null && !users.isEmpty()){
            return users.get(0);
        }
        return null;
    }

    @Override
    public User getUserByEmail(String email) {
        List<User> users = userMapper.selectByMap(Map.of("email", email));
        if(users != null && !users.isEmpty()){
            return users.get(0);
        }
        return null;
    }

    @Override
    public Result<?> resetPwd(String verifyCode, String newPwd) {
        String emailByCode = "";
        try {
            emailByCode = (String) redisTemplate.opsForValue().get("verifyCodeToResetPwd:" + verifyCode);
            if (emailByCode == null) {
                return Result.fail(Code.VERIFY_CODE_NOT_EXISTS,"验证码不存在或失效");
            }
            User user = getUserByEmail(emailByCode);
            if(user == null){
                return Result.fail(Code.USER_NOT_EXISTS,"用户不存在");
            }
            if(passwordEncoder.matches(newPwd,user.getPassword())){
                return Result.fail(Code.NEW_PWD_EQ_OLD,"新旧密码相同");
            }
            user.setPassword(passwordEncoder.encode(newPwd));
            userMapper.updateById(user.tackleTime());
            return Result.success("密码重置成功");
        }catch (Exception e){
            e.printStackTrace();
        }
        return Result.fail(Code.VERIFY_CODE_NOT_EXISTS,"验证码不存在或失效");
    }
}
