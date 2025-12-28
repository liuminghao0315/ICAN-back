package com.ican.project;

import com.ican.project.service.UserService;
import com.ican.project.utils.MailUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class Springboot3ApplicationTests {
    @Autowired
    private UserService userService;

    @Autowired
    private MailUtil mailUtil;

    @Test
    void contextLoads() {

    }

}
