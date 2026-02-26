package com.ican.project.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ican.project.mapper.UserMapper;
import com.ican.project.model.common.Code;
import com.ican.project.model.common.Constants;
import com.ican.project.model.common.Result;
import com.ican.project.model.entity.User;
import com.ican.project.service.MailService;
import com.ican.project.service.UserService;
import com.ican.project.utils.RedisCacheUtil;
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
    private Map<String, MailService> mailServiceMap;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private RedisCacheUtil redisCacheUtil;

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
            // 先从缓存获取
            String cacheKey = RedisCacheUtil.CacheKey.USER_PERMISSIONS + userId;
            @SuppressWarnings("unchecked")
            List<String> cachedPermissions = redisCacheUtil.get(cacheKey, List.class);
            if (cachedPermissions != null) {
                logger.debug("从缓存获取用户权限: userId={}", userId);
                return cachedPermissions;
            }
            
            // 缓存未命中，查询数据库
            List<String> permissions = userMapper.selectPermsByUserId(userId);
            
            // 存入缓存（包括空列表，避免重复查询）
            if (permissions != null) {
                redisCacheUtil.setUserCache(cacheKey, permissions);
            }
            
            return permissions != null ? permissions : List.of();
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
            // 先从缓存获取
            String cacheKey = RedisCacheUtil.CacheKey.USER_BY_USERNAME + username;
            User cachedUser = redisCacheUtil.get(cacheKey, User.class);
            if (cachedUser != null) {
                logger.debug("从缓存获取用户: username={}", username);
                return cachedUser;
            }
            
            // 缓存未命中，查询数据库
            List<User> users = userMapper.selectByMap(Map.of("name", username));
            User user = null;
            if (users != null && !users.isEmpty()) {
                user = users.get(0);
                // 存入缓存
                redisCacheUtil.setUserCache(cacheKey, user);
            } else {
                logger.debug("用户不存在: username={}", username);
            }
            return user;
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
            // 先从缓存获取
            String cacheKey = RedisCacheUtil.CacheKey.USER_BY_EMAIL + email;
            User cachedUser = redisCacheUtil.get(cacheKey, User.class);
            if (cachedUser != null) {
                logger.debug("从缓存获取用户: email={}", email);
                return cachedUser;
            }
            
            // 缓存未命中，查询数据库
            List<User> users = userMapper.selectByMap(Map.of("email", email));
            User user = null;
            if (users != null && !users.isEmpty()) {
                user = users.get(0);
                // 存入缓存
                redisCacheUtil.setUserCache(cacheKey, user);
            } else {
                logger.debug("用户不存在: email={}", email);
            }
            return user;
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


            String encodedPassword = passwordEncoder.encode(newPwd);
            user.setPassword(encodedPassword);
            
            User updatedUser = user.tackleTime();
            if (updatedUser == null) {
                logger.error("用户时间处理失败");
                return Result.fail(Code.INTERNAL_ERROR, "数据处理失败");
            }

            int result = userMapper.updateById(updatedUser);
            if (result > 0) {
                // 清除相关缓存（使用已查询的user对象，避免重复查询）
                if (user != null) {
                    if (user.getName() != null) {
                        redisCacheUtil.delete(RedisCacheUtil.CacheKey.USER_BY_USERNAME + user.getName());
                    }
                    redisCacheUtil.delete(RedisCacheUtil.CacheKey.USER_BY_EMAIL + emailByCode);
                    if (user.getId() != null) {
                        redisCacheUtil.delete(RedisCacheUtil.CacheKey.USER_PERMISSIONS + user.getId());
                    }
                }
                
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
    @Override
    public Result<?> sendMailToChangePwd(String userId) {
        if (userId == null) {
            return Result.fail(Code.PARAMETER_ERROR, "参数不能为空");
        }
        try {
            User user = userMapper.selectById(userId);
            if (user == null) {
                return Result.fail(Code.USER_NOT_EXISTS, "用户不存在");
            }
            String email = user.getEmail();
            if (email == null || email.trim().isEmpty()) {
                return Result.fail(Code.EMAIL_NOT_SUPPORT, "当前账号未绑定邮箱");
            }
            MailService service = com.ican.project.utils.MailServiceUtil.getMailServiceByEmail(mailServiceMap, email);
            if (service == null) {
                return Result.fail(Code.EMAIL_NOT_SUPPORT, "邮箱类型不支持");
            }
            return service.sendMailToChangePwd(email);
        } catch (Exception e) {
            logger.error("发送修改密码验证码异常: userId={}", userId, e);
            return Result.fail(Code.INTERNAL_ERROR, "发送失败，请稍后重试");
        }
    }

    @Override
    public Result<?> changePwd(String userId, String verifyCode, String newPwd) {
        if (userId == null || verifyCode == null || newPwd == null) {
            return Result.fail(Code.PARAMETER_ERROR, "参数不能为空");
        }
        if (newPwd.length() < 6 || newPwd.length() > 20) {
            return Result.fail(Code.PARAMETER_ERROR, "密码长度须在6-20个字符之间");
        }
        try {
            String redisKey = Constants.RedisKey.VERIFY_CODE_CHANGE_PWD_PREFIX + verifyCode;
            Object value = redisTemplate.opsForValue().get(redisKey);
            // value 存的是邮箱地址
            String emailByCode = value != null ? (String) value : null;
            if (emailByCode == null) {
                return Result.fail(Code.VERIFY_CODE_NOT_EXISTS, "验证码不存在或已失效");
            }
            User user = userMapper.selectById(userId);
            if (user == null) {
                return Result.fail(Code.USER_NOT_EXISTS, "用户不存在");
            }
            // 校验验证码对应的邮箱与当前用户邮箱一致
            if (!emailByCode.equals(user.getEmail())) {
                return Result.fail(Code.VERIFY_CODE_NOT_EXISTS, "验证码无效");
            }
            user.setPassword(passwordEncoder.encode(newPwd));
            user.tackleTime();
            userMapper.updateById(user);
            redisCacheUtil.delete(RedisCacheUtil.CacheKey.USER_BY_USERNAME + user.getName());
            redisCacheUtil.delete(RedisCacheUtil.CacheKey.USER_BY_EMAIL + user.getEmail());
            redisTemplate.delete(redisKey);
            logger.info("修改密码成功: userId={}", userId);
            return Result.success("密码修改成功");
        } catch (Exception e) {
            logger.error("修改密码异常: userId={}", userId, e);
            return Result.fail(Code.INTERNAL_ERROR, "修改密码失败，请稍后重试");
        }
    }

    @Override
    public Result<?> sendMailToChangeEmail(String newEmail) {
        if (newEmail == null || newEmail.trim().isEmpty()) {
            return Result.fail(Code.PARAMETER_ERROR, "新邮箱不能为空");
        }
        User existing = getUserByEmail(newEmail);
        if (existing != null) {
            return Result.fail(Code.EMAIL_EXISTS, "该邮箱已被其他账号绑定");
        }
        try {
            MailService service = com.ican.project.utils.MailServiceUtil.getMailServiceByEmail(mailServiceMap, newEmail);
            if (service == null) {
                return Result.fail(Code.EMAIL_NOT_SUPPORT, "邮箱类型不支持，请使用QQ或网易邮箱");
            }
            return service.sendMailToChangeEmail(newEmail);
        } catch (Exception e) {
            logger.error("发送变更邮箱验证码异常: newEmail={}", newEmail, e);
            return Result.fail(Code.INTERNAL_ERROR, "发送失败，请稍后重试");
        }
    }

    @Override
    public Result<?> changeEmail(String userId, String newEmail, String verifyCode) {
        if (userId == null || newEmail == null || verifyCode == null) {
            return Result.fail(Code.PARAMETER_ERROR, "参数不能为空");
        }
        try {
            String redisKey = Constants.RedisKey.VERIFY_CODE_CHANGE_EMAIL_PREFIX + verifyCode;
            Object value = redisTemplate.opsForValue().get(redisKey);
            String emailByCode = value != null ? (String) value : null;
            if (emailByCode == null || !emailByCode.equals(newEmail)) {
                return Result.fail(Code.VERIFY_CODE_NOT_EXISTS, "验证码不存在或已失效");
            }
            User user = userMapper.selectById(userId);
            if (user == null) {
                return Result.fail(Code.USER_NOT_EXISTS, "用户不存在");
            }
            String oldEmail = user.getEmail();
            user.setEmail(newEmail);
            user.tackleTime();
            userMapper.updateById(user);
            redisCacheUtil.delete(RedisCacheUtil.CacheKey.USER_BY_USERNAME + user.getName());
            if (oldEmail != null) {
                redisCacheUtil.delete(RedisCacheUtil.CacheKey.USER_BY_EMAIL + oldEmail);
            }
            redisCacheUtil.delete(RedisCacheUtil.CacheKey.USER_BY_EMAIL + newEmail);
            redisTemplate.delete(redisKey);
            logger.info("变更邮箱成功: userId={}, newEmail={}", userId, newEmail);
            return Result.success("邮箱变更成功");
        } catch (Exception e) {
            logger.error("变更邮箱异常: userId={}", userId, e);
            return Result.fail(Code.INTERNAL_ERROR, "变更邮箱失败，请稍后重试");
        }
    }
}
