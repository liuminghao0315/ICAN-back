package com.ican.project.controller;

import com.ican.project.model.common.Code;
import com.ican.project.model.common.Result;
import com.ican.project.service.LogoutService;
import com.ican.project.service.MailService;
import com.ican.project.utils.CodeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AccountController {
    @Autowired
    private LogoutService logoutService;

    @GetMapping("/account/logout")
    public Result<?> logout() {
        return logoutService.logout();
    }

}
