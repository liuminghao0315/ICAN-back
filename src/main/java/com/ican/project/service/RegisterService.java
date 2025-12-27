package com.ican.project.service;

import com.ican.project.model.common.Result;
import com.ican.project.model.dto.LoginDTO;
import com.ican.project.model.dto.RegisterDTO;

public interface RegisterService {
    Result<?> checkRegister(RegisterDTO registerDTO);
}
