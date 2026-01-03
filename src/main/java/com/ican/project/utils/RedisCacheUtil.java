package com.ican.project.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Redis 缓存工具类
 * 提供常用的缓存操作方法，减少数据库查询
 */
@Component
public class RedisCacheUtil {
    
    private static final Logger logger = LoggerFactory.getLogger(RedisCacheUtil.class);
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    /**
     * 缓存键前缀常量
     */
    public static class CacheKey {
        // 用户相关
        public static final String USER_BY_USERNAME = "cache:user:username:";
        public static final String USER_BY_EMAIL = "cache:user:email:";
        public static final String USER_PERMISSIONS = "cache:user:permissions:";
        
        // 视频相关
        public static final String VIDEO_BY_ID = "cache:video:id:";
        public static final String VIDEO_LIST = "cache:video:list:";
        
        // 分析结果相关
        public static final String RESULT_BY_ID = "cache:result:id:";
        public static final String RESULT_BY_TASK_ID = "cache:result:task:";
        public static final String RESULT_BY_VIDEO_ID = "cache:result:video:";
        public static final String RESULT_LIST = "cache:result:list:";
        public static final String RESULT_STATS = "cache:result:stats:";
        public static final String RISK_DISTRIBUTION = "cache:risk:distribution:";
        
        // 分析任务相关
        public static final String TASK_BY_ID = "cache:task:id:";
        public static final String TASK_LIST = "cache:task:list:";
    }
    
    /**
     * 默认缓存过期时间（分钟）
     */
    private static final long DEFAULT_EXPIRE_MINUTES = 30;
    
    /**
     * 用户相关缓存过期时间（分钟）
     */
    private static final long USER_CACHE_EXPIRE_MINUTES = 60;
    
    /**
     * 统计数据缓存过期时间（分钟）
     */
    private static final long STATS_CACHE_EXPIRE_MINUTES = 10;
    
    /**
     * 设置缓存
     * 
     * @param key 缓存键
     * @param value 缓存值
     * @param expireMinutes 过期时间（分钟）
     */
    public void set(String key, Object value, long expireMinutes) {
        try {
            if (value != null) {
                redisTemplate.opsForValue().set(key, value, expireMinutes, TimeUnit.MINUTES);
                logger.debug("设置缓存成功: key={}, expireMinutes={}", key, expireMinutes);
            }
        } catch (Exception e) {
            logger.error("设置缓存失败: key={}", key, e);
        }
    }
    
    /**
     * 设置缓存（使用默认过期时间）
     * 
     * @param key 缓存键
     * @param value 缓存值
     */
    public void set(String key, Object value) {
        set(key, value, DEFAULT_EXPIRE_MINUTES);
    }
    
    /**
     * 设置用户相关缓存
     * 
     * @param key 缓存键
     * @param value 缓存值
     */
    public void setUserCache(String key, Object value) {
        set(key, value, USER_CACHE_EXPIRE_MINUTES);
    }
    
    /**
     * 设置统计数据缓存
     * 
     * @param key 缓存键
     * @param value 缓存值
     */
    public void setStatsCache(String key, Object value) {
        set(key, value, STATS_CACHE_EXPIRE_MINUTES);
    }
    
    /**
     * 获取缓存
     * 
     * @param key 缓存键
     * @return 缓存值，如果不存在或异常则返回 null
     */
    public Object get(String key) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                logger.debug("缓存命中: key={}", key);
            }
            return value;
        } catch (Exception e) {
            logger.error("获取缓存失败: key={}", key, e);
            return null;
        }
    }
    
    /**
     * 获取缓存（指定类型）
     * 
     * @param key 缓存键
     * @param clazz 类型
     * @return 缓存值，如果不存在或异常则返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> clazz) {
        Object value = get(key);
        if (value != null && clazz.isInstance(value)) {
            return (T) value;
        }
        return null;
    }
    
    /**
     * 删除缓存
     * 
     * @param key 缓存键
     */
    public void delete(String key) {
        try {
            redisTemplate.delete(key);
            logger.debug("删除缓存成功: key={}", key);
        } catch (Exception e) {
            logger.error("删除缓存失败: key={}", key, e);
        }
    }
    
    /**
     * 批量删除缓存（根据模式）
     * 
     * @param pattern 键模式，例如 "cache:user:*"
     */
    public void deleteByPattern(String pattern) {
        try {
            java.util.Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                logger.debug("批量删除缓存成功: pattern={}, count={}", pattern, keys.size());
            } else {
                logger.debug("未找到匹配的缓存键: pattern={}", pattern);
            }
        } catch (Exception e) {
            logger.error("批量删除缓存失败: pattern={}", pattern, e);
        }
    }
    
    /**
     * 判断缓存是否存在
     * 
     * @param key 缓存键
     * @return 是否存在
     */
    public boolean exists(String key) {
        try {
            Boolean exists = redisTemplate.hasKey(key);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            logger.error("判断缓存是否存在失败: key={}", key, e);
            return false;
        }
    }
    
    /**
     * 设置缓存过期时间
     * 
     * @param key 缓存键
     * @param expireMinutes 过期时间（分钟）
     */
    public void expire(String key, long expireMinutes) {
        try {
            redisTemplate.expire(key, expireMinutes, TimeUnit.MINUTES);
            logger.debug("设置缓存过期时间成功: key={}, expireMinutes={}", key, expireMinutes);
        } catch (Exception e) {
            logger.error("设置缓存过期时间失败: key={}", key, e);
        }
    }
    
    /**
     * 获取并设置缓存（如果不存在）
     * 
     * @param key 缓存键
     * @param valueSupplier 值提供者（仅在缓存不存在时调用）
     * @param expireMinutes 过期时间（分钟）
     * @return 缓存值
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrSet(String key, java.util.function.Supplier<T> valueSupplier, long expireMinutes) {
        try {
            Object value = get(key);
            if (value != null) {
                return (T) value;
            }
            
            T newValue = valueSupplier.get();
            if (newValue != null) {
                set(key, newValue, expireMinutes);
            }
            return newValue;
        } catch (Exception e) {
            logger.error("获取并设置缓存失败: key={}", key, e);
            return valueSupplier.get();
        }
    }
    
    /**
     * 获取并设置缓存（如果不存在，使用默认过期时间）
     * 
     * @param key 缓存键
     * @param valueSupplier 值提供者（仅在缓存不存在时调用）
     * @return 缓存值
     */
    public <T> T getOrSet(String key, java.util.function.Supplier<T> valueSupplier) {
        return getOrSet(key, valueSupplier, DEFAULT_EXPIRE_MINUTES);
    }
}

