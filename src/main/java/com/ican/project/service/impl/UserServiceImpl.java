package com.ican.project.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ican.project.mapper.UserMapper;
import com.ican.project.model.common.Code;
import com.ican.project.model.common.Constants;
import com.ican.project.model.common.Result;
import com.ican.project.model.entity.User;
import com.ican.project.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
    private static final Logger logger = LoggerFactory.getLogger(UserServiceImpl.class);

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public List<String> getUserPermissions(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            logger.warn("用户ID为空");
            return List.of();
        }

        if (userMapper == null) {
            logger.error("UserMapper未初始化");
            return List.of();
        }

        try {
            return userMapper.selectPermsByUserId(userId);
        } catch (Exception e) {
            logger.error("获取用户权限异常: userId={}", userId, e);
            return List.of();
        }
    }

    @Override
    public User getUserByUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            logger.warn("用户名为空");
            return null;
        }

        if (userMapper == null) {
            logger.error("UserMapper未初始化");
            return null;
        }

        try {
            List<User> users = userMapper.selectByMap(Map.of("name", username));
            if (users != null && !users.isEmpty()) {
                return users.get(0);
            }
            logger.debug("用户不存在: username={}", username);
            return null;
        } catch (Exception e) {
            logger.error("根据用户名查询用户异常: username={}", username, e);
            return null;
        }
    }

    @Override
    public User getUserByEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            logger.warn("邮箱为空");
            return null;
        }

        if (userMapper == null) {
            logger.error("UserMapper未初始化");
            return null;
        }

        try {
            List<User> users = userMapper.selectByMap(Map.of("email", email));
            if (users != null && !users.isEmpty()) {
                return users.get(0);
            }
            logger.debug("用户不存在: email={}", email);
            return null;
        } catch (Exception e) {
            logger.error("根据邮箱查询用户异常: email={}", email, e);
            return null;
        }
    }

    @Override
    public Result<?> resetPwd(String verifyCode, String newPwd) {
        if (verifyCode == null || verifyCode.trim().isEmpty()) {
            logger.warn("验证码为空");
            return Result.fail(Code.PARAMETER_ERROR, "验证码不能为空");
        }

        if (newPwd == null || newPwd.trim().isEmpty()) {
            logger.warn("新密码为空");
            return Result.fail(Code.PARAMETER_ERROR, "新密码不能为空");
        }

        if (newPwd.length() < 6 || newPwd.length() > 20) {
            logger.warn("新密码长度不符合要求: length={}", newPwd.length());
            return Result.fail(Code.PARAMETER_ERROR, "密码长度必须在6-20个字符之间");
        }

        // 检查依赖服务是否初始化
        if (redisTemplate == null) {
            logger.error("Redis模板未初始化");
            return Result.fail(Code.INTERNAL_ERROR, "缓存服务未初始化");
        }

        if (passwordEncoder == null) {
            logger.error("密码编码器未初始化");
            return Result.fail(Code.INTERNAL_ERROR, "密码服务未初始化");
        }

        if (userMapper == null) {
            logger.error("UserMapper未初始化");
            return Result.fail(Code.INTERNAL_ERROR, "数据服务未初始化");
        }

        try {
            String verifyCodeKey = Constants.RedisKey.VERIFY_CODE_RESET_PWD_PREFIX + verifyCode;
            Object value = redisTemplate.opsForValue().get(verifyCodeKey);
            String emailByCode = value != null ? (String) value : null;

            if (emailByCode == null || emailByCode.trim().isEmpty()) {
                logger.warn("验证码不存在或失效: verifyCode={}", verifyCode);
                return Result.fail(Code.VERIFY_CODE_NOT_EXISTS, "验证码不存在或失效");
            }

            User user = getUserByEmail(emailByCode);
            if (user == null) {
                logger.warn("用户不存在: email={}", emailByCode);
                return Result.fail(Code.USER_NOT_EXISTS, "用户不存在");
            }

            if (user.getPassword() == null) {
                logger.error("用户密码为空: userId={}", user.getId());
                return Result.fail(Code.INTERNAL_ERROR, "用户数据异常");
            }

            if (passwordEncoder.matches(newPwd, user.getPassword())) {
                logger.warn("新旧密码相同: email={}", emailByCode);
                return Result.fail(Code.NEW_PWD_EQ_OLD, "新旧密码相同");
            }

            String encodedPassword = passwordEncoder.encode(newPwd);
            user.setPassword(encodedPassword);
            
            User updatedUser = user.tackleTime();
            if (updatedUser == null) {
                logger.error("用户时间处理失败");
                return Result.fail(Code.INTERNAL_ERROR, "数据处理失败");
            }

            int result = userMapper.updateById(updatedUser);
            if (result > 0) {
                logger.info("密码重置成功: email={}", emailByCode);
                return Result.success("密码重置成功");
            } else {
                logger.error("密码重置失败（数据库更新失败）: email={}", emailByCode);
                return Result.fail(Code.DATABASE_ERROR, "密码重置失败，请稍后重试");
            }
        } catch (Exception e) {
            logger.error("重置密码异常: verifyCode={}", verifyCode, e);
            // 保持原有逻辑：捕获异常并返回失败
            return Result.fail(Code.VERIFY_CODE_NOT_EXISTS, "密码重置失败，请稍后重试");
        }
    }
}
