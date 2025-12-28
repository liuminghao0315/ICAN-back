package com.ican.project.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 跨域配置类
 * 允许所有来源的跨域请求
 */
@Configuration
public class CorsConfig {

    /**
     * CORS配置源
     * 配置跨域请求的允许规则
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        
        // 允许所有来源（生产环境建议配置具体域名，如：config.addAllowedOrigin("http://localhost:3000")）
        config.addAllowedOriginPattern("*");
        
        // 允许所有请求头
        config.addAllowedHeader("*");
        
        // 允许所有请求方法（GET, POST, PUT, DELETE, OPTIONS等）
        config.addAllowedMethod("*");
        
        // 允许携带凭证（Cookie、Authorization等）
        config.setAllowCredentials(true);
        
        // 预检请求的缓存时间（秒），浏览器会缓存OPTIONS请求的结果
        config.setMaxAge(3600L);
        
        // 对所有路径应用CORS配置
        source.registerCorsConfiguration("/**", config);
        
        return source;
    }
}

