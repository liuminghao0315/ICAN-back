package com.ican.project.controller;

import com.ican.project.model.common.Code;
import com.ican.project.model.common.Result;
import com.ican.project.utils.MailServiceUtil;
import com.ican.project.model.entity.User;
import com.ican.project.mapper.UserMapper;
import com.ican.project.security.MyUserDetails;
import com.ican.project.service.LogoutService;
import com.ican.project.service.MailService;
import com.ican.project.service.MinioService;
import com.ican.project.service.UserAvatarService;
import com.ican.project.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
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
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private UserAvatarService userAvatarService;
    @Autowired
    private MinioService minioService;

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
    @Operation(summary = "发送重置密码邮件", description = "根据用户名或邮箱向用户邮箱发送重置密码验证码邮件")
    public Result<?> sendMailToResetPwd(
            @Parameter(description = "用户名或邮箱", required = true)
            @RequestParam("username") @NotBlank(message = "用户名或邮箱不能为空") String username) {
        String identifier = username;
        logger.info("发送重置密码邮件请求: identifier={}", identifier);
        try {
            if (userService == null) {
                logger.error("用户服务未初始化");
                return Result.fail(Code.INTERNAL_ERROR, "系统服务未初始化");
            }

            // 支持“用户名或邮箱”两种输入方式：
            // - 如果包含 @，按邮箱查询
            // - 否则按用户名查询
            User user;
            if (identifier.contains("@")) {
                logger.info("按邮箱查找用户: email={}", identifier);
                user = userService.getUserByEmail(identifier);
            } else {
                logger.info("按用户名查找用户: username={}", identifier);
                user = userService.getUserByUsername(identifier);
            }

            if (user == null) {
                logger.warn("用户不存在: identifier={}", identifier);
                return Result.fail(Code.USER_NOT_EXISTS, "用户不存在");
            }

            String email = user.getEmail();
            if (email == null || email.trim().isEmpty()) {
                logger.warn("用户邮箱为空: identifier={}", identifier);
                return Result.fail(Code.EMAIL_NOT_SUPPORT, "用户邮箱为空");
            }

            MailService service = MailServiceUtil.getMailServiceByEmail(mailServiceMap, email);
            Result<?> result;
            if (service == null) {
                result = Result.fail(Code.EMAIL_NOT_SUPPORT, "邮箱不为系统支持的邮箱");
            } else {
                result = service.sendMailToResetPwd(email);
            }

            if (result.isSuccess()) {
                logger.info("发送重置密码邮件成功: email={}", email);
            } else {
                logger.warn("发送重置密码邮件失败: email={}, reason={}", email, result.getMessage());
            }
            return result;
        } catch (Exception e) {
            logger.error("发送重置密码邮件异常: identifier={}", identifier, e);
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
            if (result.isSuccess()) {
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
    @GetMapping("/account/sendMailToChangePwd")
    @Operation(summary = "发送修改密码验证码", description = "向当前绑定邮箱发送修改密码验证码")
    public Result<?> sendMailToChangePwd(@AuthenticationPrincipal MyUserDetails userDetails) {
        if (userDetails == null) {
            return Result.fail(Code.AUTH_FAILURE, "未登录");
        }
        logger.info("发送修改密码验证码: userId={}", userDetails.getUserId());
        return userService.sendMailToChangePwd(userDetails.getUserId());
    }

    @PostMapping("/account/changePwd")
    @Operation(summary = "修改密码", description = "验证码验证后修改密码")
    public Result<?> changePwd(
            @AuthenticationPrincipal MyUserDetails userDetails,
            @Parameter(description = "验证码", required = true)
            @RequestParam("verifyCode") @NotBlank(message = "验证码不能为空") String verifyCode,
            @Parameter(description = "新密码", required = true)
            @RequestParam("newPwd") @NotBlank(message = "新密码不能为空") @Size(min = 6, max = 20, message = "密码长度须在6-20个字符之间") String newPwd) {
        if (userDetails == null) {
            return Result.fail(Code.AUTH_FAILURE, "未登录");
        }
        logger.info("修改密码请求: userId={}", userDetails.getUserId());
        return userService.changePwd(userDetails.getUserId(), verifyCode, newPwd);
    }


    @GetMapping("/account/sendMailToChangeEmail")
    @Operation(summary = "发送变更邮箱验证码", description = "向新邮箱发送验证码")
    public Result<?> sendMailToChangeEmail(
            @Parameter(description = "新邮箱地址", required = true)
            @RequestParam("newEmail") @NotBlank(message = "新邮箱不能为空") @Email(message = "邮箱格式不正确") String newEmail) {
        logger.info("发送变更邮箱验证码: newEmail={}", newEmail);
        return userService.sendMailToChangeEmail(newEmail);
    }

    @PostMapping("/account/changeEmail")
    @Operation(summary = "变更绑定邮箱", description = "验证验证码后更新邮箱")
    public Result<?> changeEmail(
            @AuthenticationPrincipal MyUserDetails userDetails,
            @Parameter(description = "新邮箱地址", required = true)
            @RequestParam("newEmail") @NotBlank(message = "新邮箱不能为空") @Email(message = "邮箱格式不正确") String newEmail,
            @Parameter(description = "验证码", required = true)
            @RequestParam("verifyCode") @NotBlank(message = "验证码不能为空") String verifyCode) {
        if (userDetails == null) {
            return Result.fail(Code.AUTH_FAILURE, "未登录");
        }
        logger.info("变更邮箱请求: userId={}", userDetails.getUserId());
        return userService.changeEmail(userDetails.getUserId(), newEmail, verifyCode);
    }
    @GetMapping("/account/me")
    @Operation(summary = "获取当前用户信息", description = "返回当前登录用户的基本信息（含角色和头像URL）")
    public Result<?> getCurrentUser(@AuthenticationPrincipal MyUserDetails userDetails) {
        if (userDetails == null) {
            return Result.fail(Code.AUTH_FAILURE, "未登录");
        }
        // 从数据库重新拉取，确保 avatarPath 是最新的
        User user = userMapper.selectById(userDetails.getUser().getId());
        if (user == null) {
            return Result.fail(Code.USER_NOT_EXISTS, "用户不存在");
        }
        List<String> roles = userMapper.selectRoleNamesByUserId(user.getId());
        String role = (roles != null && !roles.isEmpty()) ? roles.get(0) : "User";

        // 头像 URL：有 avatarPath 就通过 MinIO 生成访问链接，否则返回空字符串
        String avatarUrl = "";
        if (user.getAvatarPath() != null && !user.getAvatarPath().isBlank()) {
            try {
                avatarUrl = minioService.getFileUrl(user.getAvatarPath());
            } catch (Exception e) {
                logger.warn("获取头像URL失败: userId={}", user.getId());
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getName());
        data.put("email", user.getEmail() != null ? user.getEmail() : "");
        data.put("role", role);
        data.put("avatarUrl", avatarUrl);
        return Result.success(data);
    }

    @PostMapping("/account/avatar")
    @Operation(summary = "上传用户头像", description = "接收裁剪后的圆形头像图片，上传至 MinIO 并更新用户记录")
    public Result<?> uploadAvatar(
            @AuthenticationPrincipal MyUserDetails userDetails,
            @RequestParam("avatar") MultipartFile avatar) {
        if (userDetails == null) {
            return Result.fail(Code.AUTH_FAILURE, "未登录");
        }
        logger.info("上传头像请求: userId={}", userDetails.getUserId());
        try {
            String avatarUrl = userAvatarService.uploadAvatar(userDetails.getUserId(), avatar);
            Map<String, String> data = new HashMap<>();
            data.put("avatarUrl", avatarUrl);
            return Result.success(data);
        } catch (IllegalArgumentException e) {
            return Result.fail(Code.PARAMETER_ERROR, e.getMessage());
        }
    }
}
