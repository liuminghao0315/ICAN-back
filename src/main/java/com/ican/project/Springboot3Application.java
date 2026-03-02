package com.ican.project;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.TimeZone;

@SpringBootApplication
@MapperScan("com.ican.project.mapper")
@EnableAsync
public class Springboot3Application {

    public static void main(String[] args) {
        // 统一使用东八区，保证 LocalDateTime.now() 与数据库 DATETIME 均为北京时间
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication.run(Springboot3Application.class, args);
    }

}
