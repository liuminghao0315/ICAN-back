package com.ican.project.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 刷新Token请求DTO
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RefreshTokenDTO {
    
    @NotBlank(message = "refreshToken不能为空")
    private String refreshToken;
}

