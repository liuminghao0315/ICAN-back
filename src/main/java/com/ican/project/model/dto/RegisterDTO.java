package com.ican.project.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterDTO {
    private String username;
    private String password;
    private String email;
    private String verifyCode;

    public RegisterDTO trimMe(){
        this.username = this.username.trim();
        this.password = this.password.trim();
        this.email = this.email.trim();
        this.verifyCode = this.verifyCode.trim();
        return this;
    }
}
