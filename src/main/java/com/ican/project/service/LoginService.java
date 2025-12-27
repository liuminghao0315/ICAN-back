package com.ican.project.service;

import com.ican.project.model.common.Result;
import com.ican.project.model.dto.LoginDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;

public interface LoginService {
    Result<?>  checkLogin(LoginDTO loginDTO);
}
