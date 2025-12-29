package com.ican.project.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 双Token响应对象
 * 登录成功或刷新Token时返回
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TokenVO {
    /**
     * AccessToken - 用于接口认证，有效期15分钟
     */
    private String accessToken;

    /**
     * RefreshToken - 用于刷新AccessToken，有效期30天
     */
    private String refreshToken;

    /**
     * AccessToken过期时间（毫秒时间戳）
     */
    private Long accessTokenExpireTime;

    /**
     * RefreshToken过期时间（毫秒时间戳）
     */
    private Long refreshTokenExpireTime;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 用户名
     */
    private String username;
}

