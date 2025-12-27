package com.ican.project.controller;

import com.ican.project.model.common.Result;
import com.ican.project.model.dto.LoginDTO;
import com.ican.project.model.dto.RegisterDTO;
import com.ican.project.service.LoginService;
import com.ican.project.service.RegisterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
    @Autowired
    private LoginService loginService;

    @Autowired
    private RegisterService registerService;

    @PostMapping("/auth/login")
    public Result<?> login(@RequestBody LoginDTO loginDTO) {
        return loginService.checkLogin(loginDTO.trimMe());
    }

    @PostMapping("/auth/register")
    public Result<?> register(@RequestBody RegisterDTO registerDTO) {
        return registerService.checkRegister(registerDTO.trimMe());
    }

}
