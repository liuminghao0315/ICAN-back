package com.ican.project.controller;

import com.ican.project.model.common.Code;
import com.ican.project.model.common.Result;
import com.ican.project.model.dto.LoginDTO;
import com.ican.project.model.dto.RegisterDTO;
import com.ican.project.service.LoginService;
import com.ican.project.service.MailService;
import com.ican.project.service.RegisterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class AuthController {
    @Autowired
    private LoginService loginService;

    @Autowired
    private RegisterService registerService;

    @Autowired
    private Map<String, MailService> mailServiceMap;

    @PostMapping("/auth/login")
    public Result<?> login(@RequestBody LoginDTO loginDTO) {
        return loginService.checkLogin(loginDTO.trimMe());
    }

    @PostMapping("/auth/register")
    public Result<?> register(@RequestBody RegisterDTO registerDTO) {
        return registerService.checkRegister(registerDTO.trimMe());
    }

    @GetMapping("/auth/sendMailToRegister")
    public Result<?> sendMailToRegister(@RequestParam("mailType")String mailType, @RequestParam("mailTo")String mailTo) {
        return switch (mailType) {
            case "qq" -> mailServiceMap.get("qq").sendMailToRegister(mailTo);
            case "netease" -> mailServiceMap.get("netease").sendMailToRegister(mailTo);
            default -> Result.fail(Code.EMAIL_NOT_SUPPORT, "没有对应的邮箱");
        };
    }

}
