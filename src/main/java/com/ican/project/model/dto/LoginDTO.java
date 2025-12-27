package com.ican.project.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LoginDTO {
    private String username;
    private String password;

    public LoginDTO trimMe(){
        this.username = this.username.trim();
        this.password = this.password.trim();
        return this;
    }
}
