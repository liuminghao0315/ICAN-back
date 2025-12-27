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

    public RegisterDTO trimMe(){
        this.username = this.username.trim();
        this.password = this.password.trim();
        this.email = this.email.trim();
        return this;
    }
}
