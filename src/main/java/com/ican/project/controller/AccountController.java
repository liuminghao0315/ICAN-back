package com.ican.project.controller;

import com.ican.project.model.common.Code;
import com.ican.project.model.common.Constants;
import com.ican.project.model.common.Result;
import com.ican.project.model.entity.User;
import com.ican.project.service.LogoutService;
import com.ican.project.service.MailService;
import com.ican.project.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Tag(name = "账户管理", description = "账户相关操作接口")
public class AccountController {
    private static final Logger logger = LoggerFactory.getLogger(AccountController.class);

    @Autowired
    private LogoutService logoutService;
    @Autowired
    private Map<String, MailService> mailServiceMap;
    @Autowired
    private UserService userService;

    @GetMapping("/account/logout")
    @Operation(summary = "用户登出", description = "用户登出接口，清除登录状态")
    public Result<?> logout() {
        logger.info("用户登出请求");
        try {
            Result<?> result = logoutService.logout();
            logger.info("用户登出成功");
            return result;
        } catch (Exception e) {
            logger.error("用户登出异常", e);
            throw e;
        }
    }

    @GetMapping("/account/sendMailToResetPwd")
    @Operation(summary = "发送重置密码邮件", description = "向用户邮箱发送重置密码验证码邮件")
    public Result<?> sendMailToResetPwd(
            @Parameter(description = "用户名", required = true)
            @RequestParam("username") @NotBlank(message = "用户名不能为空") String username) {
        logger.info("发送重置密码邮件请求: username={}", username);
        try {
            if (userService == null) {
                logger.error("用户服务未初始化");
                return Result.fail(Code.INTERNAL_ERROR, "系统服务未初始化");
            }

            User user = userService.getUserByUsername(username);
            if (user == null) {
                logger.warn("用户不存在: username={}", username);
                return Result.fail(Code.USER_NOT_EXISTS, "用户不存在");
            }

            String email = user.getEmail();
            if (email == null || email.trim().isEmpty()) {
                logger.warn("用户邮箱为空: username={}", username);
                return Result.fail(Code.EMAIL_NOT_SUPPORT, "用户邮箱为空");
            }

            Result<?> result = Result.fail(Code.EMAIL_NOT_SUPPORT, "邮箱不为系统支持的邮箱");
            if (mailServiceMap == null || mailServiceMap.isEmpty()) {
                logger.error("邮件服务未初始化");
                return Result.fail(Code.INTERNAL_ERROR, "邮件服务未初始化");
            }

            if (email.endsWith(Constants.Email.QQ_SUFFIX)) {
                MailService service = mailServiceMap.get(Constants.Email.QQ_SERVICE_BEAN);
                if (service != null) {
                    result = service.sendMailToResetPwd(email);
                } else {
                    logger.warn("QQ邮箱服务不存在");
                    result = Result.fail(Code.EMAIL_NOT_SUPPORT, "QQ邮箱服务不可用");
                }
            } else if (email.endsWith(Constants.Email.NETEASE_163_SUFFIX) || email.endsWith(Constants.Email.NETEASE_126_SUFFIX)) {
                MailService service = mailServiceMap.get(Constants.Email.NETEASE_SERVICE_BEAN);
                if (service != null) {
                    result = service.sendMailToResetPwd(email);
                } else {
                    logger.warn("网易邮箱服务不存在");
                    result = Result.fail(Code.EMAIL_NOT_SUPPORT, "网易邮箱服务不可用");
                }
            } else {
                logger.warn("不支持的邮箱类型: email={}", email);
            }

            if (result.getCode().equals(Code.SUCCESS)) {
                logger.info("发送重置密码邮件成功: email={}", email);
            } else {
                logger.warn("发送重置密码邮件失败: email={}, reason={}", email, result.getMessage());
            }
            return result;
        } catch (Exception e) {
            logger.error("发送重置密码邮件异常: username={}", username, e);
            throw e;
        }
    }

    @GetMapping("/account/resetPwd")
    @Operation(summary = "重置密码", description = "通过验证码重置用户密码")
    public Result<?> resetPwd(
            @Parameter(description = "验证码", required = true)
            @RequestParam("verifyCode") @NotBlank(message = "验证码不能为空") String verifyCode,
            @Parameter(description = "新密码", required = true)
            @RequestParam("newPwd") @NotBlank(message = "新密码不能为空") @Size(min = 6, max = 20, message = "密码长度必须在6-20个字符之间") String newPwd) {
        logger.info("重置密码请求: verifyCode={}", verifyCode);
        try {
            Result<?> result = userService.resetPwd(verifyCode, newPwd);
            if (result.getCode().equals(Code.SUCCESS)) {
                logger.info("重置密码成功");
            } else {
                logger.warn("重置密码失败: reason={}", result.getMessage());
            }
            return result;
        } catch (Exception e) {
            logger.error("重置密码异常", e);
            throw e;
        }
    }
}
