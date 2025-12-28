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
}
