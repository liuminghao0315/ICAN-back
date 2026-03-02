package com.ican.project.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class User {
    private String id;
    private String name;
    private String password;
    private String email;
    /**
     * 累计分析次数（只增不减）
     */
    private Integer analysisCount;
    /**
     * 头像在 MinIO 中的对象路径，例如 avatar/userId/avatar.jpg
     */
    private String avatarPath;
    private LocalDateTime gmtCreated;
    private LocalDateTime gmtModified;

    public User(String name, String password, String email) {
        this.id=null;
        this.name = name;
        this.password = password;
        this.email = email;
        this.analysisCount = 0;
        this.tackleTime();
    }

    public User tackleTime(){
        if(gmtCreated == null){
            gmtCreated = LocalDateTime.now();
        }
        gmtModified = LocalDateTime.now();
        return this;
    }
}
