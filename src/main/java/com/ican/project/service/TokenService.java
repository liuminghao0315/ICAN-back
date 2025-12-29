package com.ican.project.service;

import com.ican.project.model.common.Result;
import com.ican.project.model.vo.TokenVO;

/**
 * Token服务接口
 * 处理Token刷新和登出
 */
public interface TokenService {
    
    /**
     * 使用RefreshToken刷新AccessToken
     * @param refreshToken RefreshToken
     * @return 新的Token对
     */
    Result<TokenVO> refreshToken(String refreshToken);
    
    /**
     * 登出（使Token失效）
     * @param userId 用户ID
     * @return 结果
     */
    Result<String> logout(String userId);
}

