package com.ican.project.service.impl;

import com.ican.project.mapper.UserMapper;
import com.ican.project.model.common.Code;
import com.ican.project.model.common.Constants;
import com.ican.project.model.common.Result;
import com.ican.project.model.dto.RegisterDTO;
import com.ican.project.model.entity.User;
import com.ican.project.service.RegisterService;
import com.ican.project.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class RegisterServiceImpl implements RegisterService {
    private static final Logger logger = LoggerFactory.getLogger(RegisterServiceImpl.class);

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public Result<?> checkRegister(RegisterDTO registerDTO) {
        if (registerDTO == null) {
            logger.error("注册DTO为空");
            return Result.fail(Code.PARAMETER_ERROR, "注册信息不能为空");
        }

        String username = registerDTO.getUsername();
        String password = registerDTO.getPassword();
        String email = registerDTO.getEmail();
        String verifyCode = registerDTO.getVerifyCode();

        // 参数空值检查（虽然已经有@Valid注解，但这里再检查一次增强健壮性）
        if (username == null || username.trim().isEmpty()) {
            logger.warn("用户名为空");
            return Result.fail(Code.PARAMETER_ERROR, "用户名不能为空");
        }

        if (password == null || password.trim().isEmpty()) {
            logger.warn("密码为空");
            return Result.fail(Code.PARAMETER_ERROR, "密码不能为空");
        }

        if (email == null || email.trim().isEmpty()) {
            logger.warn("邮箱为空");
            return Result.fail(Code.PARAMETER_ERROR, "邮箱不能为空");
        }

        if (verifyCode == null || verifyCode.trim().isEmpty()) {
            logger.warn("验证码为空");
            return Result.fail(Code.PARAMETER_ERROR, "验证码不能为空");
        }

        // 检查依赖服务是否初始化
        if (userService == null) {
            logger.error("用户服务未初始化");
            return Result.fail(Code.INTERNAL_ERROR, "用户服务未初始化");
        }

        if (userMapper == null) {
            logger.error("用户Mapper未初始化");
            return Result.fail(Code.INTERNAL_ERROR, "数据服务未初始化");
        }

        if (redisTemplate == null) {
            logger.error("Redis模板未初始化");
            return Result.fail(Code.INTERNAL_ERROR, "缓存服务未初始化");
        }

        if (passwordEncoder == null) {
            logger.error("密码编码器未初始化");
            return Result.fail(Code.INTERNAL_ERROR, "密码服务未初始化");
        }

        try {
            // 检查用户名是否已存在
            if (userService.getUserByUsername(username) != null) {
                logger.warn("用户已存在: username={}", username);
                return Result.fail(Code.USER_EXISTS, "用户已存在");
            }

            // 检查邮箱是否已存在
            if (userService.getUserByEmail(email) != null) {
                logger.warn("邮箱已存在: email={}", email);
                return Result.fail(Code.EMAIL_EXISTS, "邮箱已存在");
            }

            // 验证验证码
            String verifyCodeKey = Constants.RedisKey.VERIFY_CODE_REGISTER_PREFIX + verifyCode;
            String emailByCode;
            try {
                Object value = redisTemplate.opsForValue().get(verifyCodeKey);
                emailByCode = value != null ? (String) value : null;
                
                if (emailByCode == null || !emailByCode.equals(email)) {
                    logger.warn("验证码无效或过期: verifyCode={}, email={}", verifyCode, email);
                    return Result.fail(Code.VERIFY_CODE_NOT_EXISTS, "验证码过期或不存在");
                }
            } catch (Exception e) {
                logger.error("验证码验证异常: verifyCode={}", verifyCode, e);
                return Result.fail(Code.VERIFY_CODE_NOT_EXISTS, "验证码验证失败");
            }

            // 删除验证码
            try {
                redisTemplate.delete(verifyCodeKey);
                logger.debug("验证码已删除: verifyCode={}", verifyCode);
            } catch (Exception e) {
                logger.warn("删除验证码失败: verifyCode={}", verifyCode, e);
                // 继续执行，不影响注册流程
            }

            // 创建用户
            String encodedPassword = passwordEncoder.encode(password);
            User user = new User(username, encodedPassword, email);
            
            try {
                int result = userMapper.insert(user);
                if (result > 0) {
                    logger.info("用户注册成功: username={}, email={}", username, email);
                    return Result.success(registerDTO);
                } else {
                    logger.error("用户注册失败（数据库插入失败）: username={}, email={}", username, email);
                    return Result.fail(Code.DATABASE_ERROR, "注册失败，请稍后重试");
                }
            } catch (Exception e) {
                logger.error("数据库插入异常: username={}, email={}", username, email, e);
                // 保持原有逻辑：捕获异常并返回失败
                return Result.fail(Code.DATABASE_ERROR, "注册失败，请稍后重试");
            }

        } catch (Exception e) {
            logger.error("注册异常: username={}, email={}", username, email, e);
            // 保持原有逻辑：捕获异常并返回失败
            return Result.fail(Code.VERIFY_CODE_NOT_EXISTS, "注册失败，请稍后重试");
        }
    }
}
