package com.ican.project.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ican.project.model.common.Result;
import com.ican.project.model.entity.User;

import java.util.List;

public interface UserService extends IService<User> {
    List<String> getUserPermissions(String userId);

    Result<?> resetPwd(String verifyCode, String newPwd);

    /**
     * 根据用户名获取用户
     * @param username 用户名
     * @return 用户对象，如果不存在则返回null
     */
    User getUserByUsername(String username);

    /**
     * 根据邮箱获取用户
     * @param email 邮箱
     * @return 用户对象，如果不存在则返回null
     */
    User getUserByEmail(String email);

    /**
     * 发送修改密码验证码到当前绑定邮箱
     */
    Result<?> sendMailToChangePwd(String userId);

    /**
     * 验证码验证后修改密码
     */
    Result<?> changePwd(String userId, String verifyCode, String newPwd);

    /**
     * 发送变更邮箱验证码到新邮箱
     */
    Result<?> sendMailToChangeEmail(String newEmail);

    /**
     * 验证验证码并更新邮箱
     */
    Result<?> changeEmail(String userId, String newEmail, String verifyCode);
}
