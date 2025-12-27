package com.ican.project.controller;

import com.ican.project.mapper.UserMapper;
import com.ican.project.model.common.Code;
import com.ican.project.model.common.Result;
import com.ican.project.model.entity.User;
import com.ican.project.service.LogoutService;
import com.ican.project.service.MailService;
import com.ican.project.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "账户管理", description = "账户相关操作接口")
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
    @Operation(summary = "用户登出", description = "用户登出接口，清除登录状态")
    public Result<?> logout() {
        return logoutService.logout();
    }

    @GetMapping("/account/sendMailToResetPwd")
    @Operation(summary = "发送重置密码邮件", description = "向用户邮箱发送重置密码验证码邮件")
    public Result<?> sendMailToResetPwd(
            @Parameter(description = "用户名", required = true)
            @RequestParam("username")String username){
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
    @Operation(summary = "重置密码", description = "通过验证码重置用户密码")
    public Result<?> resetPwd(
            @Parameter(description = "验证码", required = true)
            @RequestParam("verifyCode")String verifyCode,
            @Parameter(description = "新密码", required = true)
            @RequestParam("newPwd")String newPwd){
        return userService.resetPwd(verifyCode,newPwd);
    }
}
