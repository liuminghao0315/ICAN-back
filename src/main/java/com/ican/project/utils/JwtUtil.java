package com.ican.project.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

import java.util.Date;
import java.util.UUID;

public class JwtUtil {
    //私钥
    private static final String SECRET = "icanProject";
    /**
     * 生成jwt
     */
    public static String createToken(String subject, long expire) {
        return Jwts.builder()
            //头部
            .setHeaderParam("typ", "JWT")            // 令牌的类型为JWT令牌
            .setHeaderParam("alg", "HS256")            // 签名的加密算法HS256
            //载荷
            .setId(UUID.randomUUID().toString())    //唯一标识
            .setIssuer("laoli")                        //签发人
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + expire))
            .setSubject(subject)
            //签名
            .signWith(SignatureAlgorithm.HS256, SECRET)
            .compact();
    }

    /**
     * 解析jwt
     */
    public static Claims parseToken(String token) {
        return Jwts.parser()
            .setSigningKey(SECRET)
            .parseClaimsJws(token)
            .getBody();
    }

}