package com.ican.project.model.common;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class NotFilterPaths {
    public String[] accuratePaths = new String[]{
            "/account/sendMailToResetPwd",
            "/account/resetPwd",
            "/swagger-ui.html",
            "/swagger-ui/index.html",
            "/v3/api-docs"
    };

    //格式："/path/"或"/path1/path2/
    public String[] startWithPathsInFilter = new String[]{
            "/auth/",
            "/test/",
            "/swagger-ui/",
            "/v3/api-docs/",
            "/ws/",                    // WebSocket路径
            "/api/algorithm/"          // 算法回调路径（生产环境应加安全验证）
    };
    public String[] startWithPathsInConfig;

    @PostConstruct
    public void init() {
        startWithPathsInConfig = Arrays.stream(startWithPathsInFilter).map(s->s+="**").toArray(String[]::new);
    }
}