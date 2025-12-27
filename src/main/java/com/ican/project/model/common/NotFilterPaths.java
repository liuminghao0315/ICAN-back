package com.ican.project.model.common;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class NotFilterPaths {
    public String[] accuratePaths = new String[]{
            "/account/sendMailToResetPwd",
            "/account/resetPwd"
    };

    //格式："/path/"或"/path1/path2/
    public String[] startWithPathsInFilter = new String[]{
            "/auth/",
            "/test/"
    };
    public String[] startWithPathsInConfig;

    @PostConstruct
    public void init() {
        startWithPathsInConfig = Arrays.stream(startWithPathsInFilter).map(s->s+="**").toArray(String[]::new);
    }
}