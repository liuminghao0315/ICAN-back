package com.ican.project.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class User {
    private String id;
    private String name;
    private String password;
    private String email;

    public User(String name, String password, String email) {
        this.id=null;
        this.name = name;
        this.password = password;
        this.email = email;
    }
}
