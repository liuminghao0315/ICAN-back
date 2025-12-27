package com.ican.project.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfiguration {
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        // 设置连接工厂
        template.setConnectionFactory(connectionFactory);
        // key采用StringRedisSerializer类序列化
        template.setKeySerializer(new StringRedisSerializer());
        // value采用GenericJackson2JsonRedisSerializer类序列化
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        // hashkey采用StringRedisSerializer类序列化
        template.setHashKeySerializer(new StringRedisSerializer());
        // hashkey采用GenericJackson2JsonRedisSerializer类序列化
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }
}
