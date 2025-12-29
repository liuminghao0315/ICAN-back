package com.ican.project.utils;

import com.ican.project.model.common.Constants;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

import java.util.Date;
import java.util.UUID;

/**
 * JWT工具类 - 支持双Token机制
 * AccessToken: 短期有效（15分钟），用于接口认证
 * RefreshToken: 长期有效（30天），用于刷新AccessToken
 */
public class JwtUtil {
    // 私钥 - 生产环境建议使用更长的密钥并从配置中读取
    private static final String SECRET = "icanProjectSecretKeyForJwtToken";

    /**
     * 创建AccessToken
     * @param userId 用户ID
     * @return AccessToken
     */
    public static String createAccessToken(String userId) {
        long expireMillis = Constants.Token.ACCESS_TOKEN_EXPIRE_MINUTES * 60 * 1000;
        return createToken(userId, Constants.Token.TYPE_ACCESS, expireMillis);
    }

    /**
     * 创建RefreshToken
     * @param userId 用户ID
     * @return RefreshToken
     */
    public static String createRefreshToken(String userId) {
        long expireMillis = Constants.Token.REFRESH_TOKEN_EXPIRE_DAYS * 24 * 60 * 60 * 1000;
        return createToken(userId, Constants.Token.TYPE_REFRESH, expireMillis);
    }

    /**
     * 获取AccessToken过期时间戳
     * @return 毫秒时间戳
     */
    public static long getAccessTokenExpireTime() {
        return System.currentTimeMillis() + Constants.Token.ACCESS_TOKEN_EXPIRE_MINUTES * 60 * 1000;
    }

    /**
     * 获取RefreshToken过期时间戳
     * @return 毫秒时间戳
     */
    public static long getRefreshTokenExpireTime() {
        return System.currentTimeMillis() + Constants.Token.REFRESH_TOKEN_EXPIRE_DAYS * 24 * 60 * 60 * 1000;
    }

    /**
     * 生成JWT Token
     * @param subject 主题（用户ID）
     * @param tokenType Token类型（access/refresh）
     * @param expireMillis 过期时间（毫秒）
     * @return JWT Token
     */
    public static String createToken(String subject, String tokenType, long expireMillis) {
        return Jwts.builder()
                // 头部
                .setHeaderParam("typ", "JWT")
                .setHeaderParam("alg", "HS256")
                // 载荷
                .setId(UUID.randomUUID().toString())
                .setIssuer("ican-project")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expireMillis))
                .setSubject(subject)
                .claim("type", tokenType)  // 标记Token类型
                // 签名
                .signWith(SignatureAlgorithm.HS256, SECRET)
                .compact();
    }

    /**
     * 兼容旧方法 - 生成JWT Token
     * @deprecated 请使用 createAccessToken 或 createRefreshToken
     */
    @Deprecated
    public static String createToken(String subject, long expireMillis) {
        return createToken(subject, Constants.Token.TYPE_ACCESS, expireMillis);
    }

    /**
     * 解析JWT Token（会校验过期时间）
     * @param token JWT Token
     * @return Claims
     * @throws ExpiredJwtException 如果Token已过期
     */
    public static Claims parseToken(String token) {
        return Jwts.parser()
                .setSigningKey(SECRET)
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * 解析JWT Token（忽略过期时间，用于刷新场景）
     * @param token JWT Token
     * @return Claims 或 null（如果解析失败）
     */
    public static Claims parseTokenIgnoreExpiration(String token) {
        try {
            return Jwts.parser()
                    .setSigningKey(SECRET)
                    .parseClaimsJws(token)
                    .getBody();
        } catch (ExpiredJwtException e) {
            // Token过期时，仍然可以获取到Claims
            return e.getClaims();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取Token类型
     * @param claims JWT Claims
     * @return Token类型（access/refresh）
     */
    public static String getTokenType(Claims claims) {
        Object type = claims.get("type");
        return type != null ? type.toString() : Constants.Token.TYPE_ACCESS;
    }

    /**
     * 判断Token是否为AccessToken
     * @param claims JWT Claims
     * @return true if AccessToken
     */
    public static boolean isAccessToken(Claims claims) {
        return Constants.Token.TYPE_ACCESS.equals(getTokenType(claims));
    }

    /**
     * 判断Token是否为RefreshToken
     * @param claims JWT Claims
     * @return true if RefreshToken
     */
    public static boolean isRefreshToken(Claims claims) {
        return Constants.Token.TYPE_REFRESH.equals(getTokenType(claims));
    }

    /**
     * 检查Token是否已过期
     * @param token JWT Token
     * @return true if expired
     */
    public static boolean isTokenExpired(String token) {
        try {
            Claims claims = parseToken(token);
            return claims.getExpiration().before(new Date());
        } catch (ExpiredJwtException e) {
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 从Token中获取用户ID（忽略过期）
     * @param token JWT Token
     * @return 用户ID 或 null
     */
    public static String getUserIdFromToken(String token) {
        Claims claims = parseTokenIgnoreExpiration(token);
        return claims != null ? claims.getSubject() : null;
    }
}
