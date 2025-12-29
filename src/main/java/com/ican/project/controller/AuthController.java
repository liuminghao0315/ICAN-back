package com.ican.project.controller;

import com.ican.project.model.common.Result;
import com.ican.project.model.dto.RefreshTokenDTO;
import com.ican.project.utils.MailServiceUtil;
import com.ican.project.model.dto.LoginDTO;
import com.ican.project.model.dto.RegisterDTO;
import com.ican.project.model.vo.TokenVO;
import com.ican.project.service.LoginService;
import com.ican.project.service.MailService;
import com.ican.project.service.RegisterService;
import com.ican.project.service.TokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@Tag(name = "认证管理", description = "用户登录、注册等认证相关接口")
public class AuthController {
    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    @Autowired
    private LoginService loginService;

    @Autowired
    private RegisterService registerService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private Map<String, MailService> mailServiceMap;

    @PostMapping("/auth/login")
    @Operation(summary = "用户登录", description = "用户登录接口，返回双Token（accessToken + refreshToken）")
    public Result<?> login(@Valid @RequestBody LoginDTO loginDTO) {
        logger.info("用户登录请求: username={}", loginDTO.getUsername());
        try {
            Result<?> result = loginService.checkLogin(loginDTO.trimMe());
            if (result.isSuccess()) {
                logger.info("用户登录成功: username={}", loginDTO.getUsername());
            } else {
                logger.warn("用户登录失败: username={}, reason={}", loginDTO.getUsername(), result.getMessage());
            }
            return result;
        } catch (Exception e) {
            logger.error("用户登录异常: username={}", loginDTO.getUsername(), e);
            throw e;
        }
    }

    @PostMapping("/auth/refresh")
    @Operation(summary = "刷新Token", description = "使用refreshToken刷新accessToken，实现无感刷新")
    public Result<TokenVO> refreshToken(@Valid @RequestBody RefreshTokenDTO refreshTokenDTO) {
        logger.info("刷新Token请求");
        try {
            Result<TokenVO> result = tokenService.refreshToken(refreshTokenDTO.getRefreshToken());
            if (result.isSuccess()) {
                logger.info("Token刷新成功");
            } else {
                logger.warn("Token刷新失败: reason={}", result.getMessage());
            }
            return result;
        } catch (Exception e) {
            logger.error("Token刷新异常", e);
            throw e;
        }
    }

    @PostMapping("/auth/register")
    @Operation(summary = "用户注册", description = "新用户注册接口")
    public Result<?> register(@Valid @RequestBody RegisterDTO registerDTO) {
        logger.info("用户注册请求: username={}, email={}", registerDTO.getUsername(), registerDTO.getEmail());
        try {
            Result<?> result = registerService.checkRegister(registerDTO.trimMe());
            if (result.isSuccess()) {
                logger.info("用户注册成功: username={}, email={}", registerDTO.getUsername(), registerDTO.getEmail());
            } else {
                logger.warn("用户注册失败: username={}, email={}, reason={}", 
                        registerDTO.getUsername(), registerDTO.getEmail(), result.getMessage());
            }
            return result;
        } catch (Exception e) {
            logger.error("用户注册异常: username={}, email={}", registerDTO.getUsername(), registerDTO.getEmail(), e);
            throw e;
        }
    }

    @GetMapping("/auth/sendMailToRegister")
    @Operation(summary = "发送注册验证邮件", description = "向指定邮箱发送注册验证码邮件")
    public Result<?> sendMailToRegister(
            @Parameter(description = "邮箱类型，支持：qq、netease", required = true)
            @RequestParam("mailType") @NotBlank(message = "邮箱类型不能为空") String mailType,
            @Parameter(description = "接收邮件的邮箱地址", required = true)
            @RequestParam("mailTo") @NotBlank(message = "邮箱地址不能为空") @Email(message = "邮箱格式不正确") String mailTo) {
        logger.info("发送注册验证邮件请求: mailType={}, mailTo={}", mailType, mailTo);
        try {
            // 验证并获取邮件服务
            Result<?> validateResult = MailServiceUtil.validateAndGetMailService(mailServiceMap, mailType);
            if (validateResult != null) {
                return validateResult;
            }

            // 获取邮件服务并发送（validateAndGetMailService已经确保service不为null）
            MailService service = MailServiceUtil.getMailServiceByType(mailServiceMap, mailType);
            Result<?> result = service.sendMailToRegister(mailTo);
            if (result.isSuccess()) {
                logger.info("发送注册验证邮件成功: mailTo={}", mailTo);
            } else {
                logger.warn("发送注册验证邮件失败: mailTo={}, reason={}", mailTo, result.getMessage());
            }
            return result;
        } catch (Exception e) {
            logger.error("发送注册验证邮件异常: mailType={}, mailTo={}", mailType, mailTo, e);
            throw e;
        }
    }
}
