package com.ican.project.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ican.project.model.common.Result;
import com.ican.project.model.entity.User;

import java.util.List;

public interface UserService extends IService<User> {
    List<String> getUserPermissions(String userId);

    Result<?> resetPwd(String verifyCode, String newPwd);
}
