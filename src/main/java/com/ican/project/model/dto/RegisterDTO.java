package com.ican.project.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "用户注册请求DTO")
public class RegisterDTO {
    @Schema(description = "用户名", example = "testuser", requiredMode = Schema.RequiredMode.REQUIRED)
    private String username;
    @Schema(description = "密码", example = "123456", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;
    @Schema(description = "邮箱地址", example = "test@qq.com", requiredMode = Schema.RequiredMode.REQUIRED)
    private String email;
    @Schema(description = "邮箱验证码", example = "123456", requiredMode = Schema.RequiredMode.REQUIRED)
    private String verifyCode;

    public RegisterDTO trimMe(){
        this.username = this.username.trim();
        this.password = this.password.trim();
        this.email = this.email.trim();
        this.verifyCode = this.verifyCode.trim();
        return this;
    }
}
