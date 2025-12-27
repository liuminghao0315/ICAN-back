package com.ican.project.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfiguration {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // 1. 构建一个定制的 ObjectMapper
        ObjectMapper objectMapper = new ObjectMapper();

        // 【关键修复】：注册 JavaTimeModule，解决 LocalDateTime 序列化报错问题
        objectMapper.registerModule(new JavaTimeModule());

        // (可选) 设置为 false，让日期序列化为 "2023-01-01 10:00:00" 字符串，而不是时间戳数字数组，方便在 Redis 客户端查看
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // 设置可见性：序列化所有字段（包括 private）
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);

        // 启用类型信息：这样 Redis 才知道存的是 "User" 对象，而不是简单的 Map
        // 注意：如果你用的 Jackson 版本较老，这里可能需要用 enableDefaultTyping
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);

        // 2. 使用 Jackson2JsonRedisSerializer (它允许我们传入自定义的 objectMapper)
        Jackson2JsonRedisSerializer<Object> serializer = new Jackson2JsonRedisSerializer<>(objectMapper, Object.class);

        // 3. 设置 Key 和 Value 的序列化规则
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer); // 使用上面的 serializer
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);

        template.afterPropertiesSet();
        return template;
    }
}
