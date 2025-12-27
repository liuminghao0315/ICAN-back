package com.ican.project.controller;

import com.ican.project.model.common.Code;
import com.ican.project.model.common.Result;
import com.ican.project.model.dto.LoginDTO;
import com.ican.project.model.dto.RegisterDTO;
import com.ican.project.service.LoginService;
import com.ican.project.service.MailService;
import com.ican.project.service.RegisterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@Tag(name = "认证管理", description = "用户认证相关接口，包括登录、注册、邮箱验证等功能")
public class AuthController {
    @Autowired
    private LoginService loginService;

    @Autowired
    private RegisterService registerService;

    @Autowired
    private Map<String, MailService> mailServiceMap;

    @PostMapping("/auth/login")
    @Operation(summary = "用户登录", description = "用户登录接口，验证用户名和密码")
    public Result<?> login(@RequestBody LoginDTO loginDTO) {
        return loginService.checkLogin(loginDTO.trimMe());
    }

    @PostMapping("/auth/register")
    @Operation(summary = "用户注册", description = "用户注册接口，创建新用户账户")
    public Result<?> register(@RequestBody RegisterDTO registerDTO) {
        return registerService.checkRegister(registerDTO.trimMe());
    }

    @GetMapping("/auth/sendMailToRegister")
    @Operation(summary = "发送注册验证邮件", description = "向指定邮箱发送注册验证码邮件")
    public Result<?> sendMailToRegister(
            @Parameter(description = "邮箱类型，支持 qq 或 netease", required = true) @RequestParam("mailType")String mailType,
            @Parameter(description = "目标邮箱地址", required = true) @RequestParam("mailTo")String mailTo) {
        return switch (mailType) {
            case "qq" -> mailServiceMap.get("qq").sendMailToRegister(mailTo);
            case "netease" -> mailServiceMap.get("netease").sendMailToRegister(mailTo);
            default -> Result.fail(Code.EMAIL_NOT_SUPPORT, "没有对应的邮箱");
        };
    }



}
