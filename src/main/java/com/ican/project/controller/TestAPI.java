package com.ican.project.controller;

import com.ican.project.model.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "测试接口", description = "测试和示例接口")
public class TestAPI {
    @GetMapping("/api/A")
    @PreAuthorize("@myValidator.validateAuthority('api:A')")
    @Operation(summary = "API A", description = "需要 api:A 权限的测试接口")
    public Result<?> apiA() {
        return Result.success("api A");
    }

    @GetMapping("/api/B")
    @PreAuthorize("@myValidator.validateAuthority('api:B')")
    @Operation(summary = "API B", description = "需要 api:B 权限的测试接口")
    public Result<?> apiB() {
        return Result.success("api B");
    }





    @GetMapping("/test/1")
    @Operation(summary = "测试接口1", description = "简单的测试接口，返回 hello world")
    public Result<?> test1() {
        return Result.success("hello world");
    }

    @Autowired
    private RedisTemplate<String,Object> redisTemplate;

    @GetMapping("/test/2")
    @Operation(summary = "测试接口2", description = "Redis 设置测试接口")
    public Result<?> test2() {
        redisTemplate.opsForValue().set("key","value");
        return Result.success("redis set");
    }

    @GetMapping("/test/3")
    @Operation(summary = "测试接口3", description = "Redis 获取测试接口")
    public Result<?> test3() {
        Object key = redisTemplate.opsForValue().get("key");
        return Result.success("redis get:"+key);
    }

    @GetMapping("/resource/1")
    @Operation(summary = "资源接口1", description = "资源测试接口")
    public Result<?> resource1() {
        return Result.success("resource 1");
    }
}
