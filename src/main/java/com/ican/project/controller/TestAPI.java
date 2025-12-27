package com.ican.project.controller;

import com.ican.project.model.common.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestAPI {
    @GetMapping("/api/A")
    @PreAuthorize("@myValidator.validateAuthority('api:A')")
    public Result<?> apiA() {
        return Result.success("api A");
    }

    @GetMapping("/api/B")
    @PreAuthorize("@myValidator.validateAuthority('api:B')")
    public Result<?> apiB() {
        return Result.success("api B");
    }





    @GetMapping("/test/1")
    public Result<?> test1() {
        return Result.success("hello world");
    }

    @Autowired
    private RedisTemplate<String,Object> redisTemplate;

    @GetMapping("/test/2")
    public Result<?> test2() {
        redisTemplate.opsForValue().set("key","value");
        return Result.success("redis set");
    }

    @GetMapping("/test/3")
    public Result<?> test3() {
        Object key = redisTemplate.opsForValue().get("key");
        return Result.success("redis get:"+key);
    }

    @GetMapping("/resource/1")
    public Result<?> resource1() {
        return Result.success("resource 1");
    }
}
