package com.ican.project;

import com.ican.project.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class Springboot3ApplicationTests {
    @Autowired
    private UserService userService;

    @Test
    void contextLoads() {
    }

}
