package com.ican.project.controller;

import com.ican.project.mapper.UserMapper;
import com.ican.project.model.common.Code;
import com.ican.project.model.common.Result;
import com.ican.project.model.entity.User;
import com.ican.project.service.LogoutService;
import com.ican.project.service.MailService;
import com.ican.project.service.UserService;
import com.ican.project.utils.CodeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class AccountController {
    @Autowired
    private LogoutService logoutService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private Map<String, MailService> mailServiceMap;
    @Autowired
    private UserService userService;

    @GetMapping("/account/logout")
    public Result<?> logout() {
        return logoutService.logout();
    }

    @GetMapping("/account/sendMailToResetPwd")
    public Result<?> sendMailToResetPwd(@RequestParam("username")String username){
        List<User> users = userMapper.selectByMap(Map.of("name", username));
        if(users==null || users.isEmpty()){
            return Result.fail(Code.USER_NOT_EXISTS,"用户不存在");
        }
        User user = users.get(0);
        String email = user.getEmail();
        Result<?> result = Result.fail(Code.EMAIL_NOT_SUPPORT,"邮箱不为系统支持的邮箱");
        if(email.endsWith("@qq.com")){
            result = mailServiceMap.get("qq").sendMailToResetPwd(email);
        }else if(email.endsWith("@163.com")||email.endsWith("@126.com")){
            result = mailServiceMap.get("netease").sendMailToResetPwd(email);
        }
        return result;
    }

    @GetMapping("/account/resetPwd")
    public Result<?> resetPwd(@RequestParam("verifyCode")String verifyCode,@RequestParam("newPwd")String newPwd){
        return userService.resetPwd(verifyCode,newPwd);
    }
}
