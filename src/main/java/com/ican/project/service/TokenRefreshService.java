package com.ican.project.service;

import com.ican.project.model.common.Result;
import com.ican.project.model.dto.TokenResponse;

public interface TokenRefreshService {
    /**
     * 使用refreshToken刷新accessToken
     * @param refreshToken 刷新令牌
     * @return 新的TokenResponse，包含新的accessToken和refreshToken
     */
    Result<TokenResponse> refreshToken(String refreshToken);
}

