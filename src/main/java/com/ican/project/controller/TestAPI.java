package com.ican.project.controller;

import com.ican.project.model.common.Code;
import com.ican.project.model.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "测试接口", description = "测试和示例接口")
public class TestAPI {
    private static final Logger logger = LoggerFactory.getLogger(TestAPI.class);

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @GetMapping("/api/A")
    @PreAuthorize("@myValidator.validateAuthority('api:A')")
    @Operation(summary = "API A", description = "需要 api:A 权限的测试接口", security = @SecurityRequirement(name = "Bearer Authentication"))
    public Result<?> apiA() {
        logger.debug("调用API A");
        return Result.success("api A");
    }

    @GetMapping("/api/B")
    @PreAuthorize("@myValidator.validateAuthority('api:B')")
    @Operation(summary = "API B", description = "需要 api:B 权限的测试接口", security = @SecurityRequirement(name = "Bearer Authentication"))
    public Result<?> apiB() {
        logger.debug("调用API B");
        return Result.success("api B");
    }

    @GetMapping("/test/1")
    @Operation(summary = "测试接口1", description = "简单的测试接口，返回 hello world")
    public Result<?> test1() {
        logger.debug("调用测试接口1");
        return Result.success("hello world");
    }

    @GetMapping("/test/2")
    @Operation(summary = "测试接口2", description = "Redis 测试接口，设置键值对")
    public Result<?> test2() {
        logger.debug("调用测试接口2（Redis设置）");
        try {
            if (redisTemplate == null) {
                logger.error("Redis模板未初始化");
                return Result.fail(Code.INTERNAL_ERROR, "Redis服务未初始化");
            }
            redisTemplate.opsForValue().set("key", "value");
            logger.debug("Redis设置成功: key=key, value=value");
            return Result.success("redis set");
        } catch (Exception e) {
            logger.error("Redis设置异常", e);
            throw e; // 让全局异常处理器处理
        }
    }

    @GetMapping("/test/3")
    @Operation(summary = "测试接口3", description = "Redis 测试接口，获取键值")
    public Result<?> test3() {
        logger.debug("调用测试接口3（Redis获取）");
        try {
            if (redisTemplate == null) {
                logger.error("Redis模板未初始化");
                return Result.fail(Code.INTERNAL_ERROR, "Redis服务未初始化");
            }
            Object key = redisTemplate.opsForValue().get("key");
            logger.debug("Redis获取成功: key=key, value={}", key);
            return Result.success("redis get:" + (key != null ? key : "null"));
        } catch (Exception e) {
            logger.error("Redis获取异常", e);
            throw e; // 让全局异常处理器处理
        }
    }

    @GetMapping("/resource/1")
    @Operation(summary = "资源接口1", description = "资源测试接口")
    public Result<?> resource1() {
        logger.debug("调用资源接口1");
        return Result.success("resource 1");
    }
}
