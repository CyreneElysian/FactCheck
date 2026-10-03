package com.factcheck.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具类，负责 token 的生成与解析
 */
public class JwtUtil {
    /**
     * 生成 JWT token
     *
     * <p>注意：jjwt 会把 {@code claims} 直接包进内部 map，并在设置过期时间时**就地写入**，
     * 因此这里先复制一份——否则调用方传不可变 Map（如 {@code Map.of(...)}）会抛
     * {@code UnsupportedOperationException}，传普通 Map 也会被悄悄塞进 {@code exp} 字段。
     * 这个问题由 P5-58 的 WebSocket 测试暴露（生产调用恰好都传了可变 Map 才没踩到）。</p>
     *
     * @param secretKey 签名密钥
     * @param ttlMillis 过期时间（毫秒）
     * @param claims    自定义声明（不会被修改）
     * @return token
     */
    public static String createJWT(String secretKey, long ttlMillis, Map<String, Object> claims) {
        SignatureAlgorithm signatureAlgorithm = SignatureAlgorithm.HS256;
        long expMillis = System.currentTimeMillis() + ttlMillis;
        Date exp = new Date(expMillis);

        JwtBuilder builder = Jwts.builder()
                .setClaims(claims == null ? new HashMap<>() : new HashMap<>(claims))
                .signWith(signatureAlgorithm, secretKey.getBytes(StandardCharsets.UTF_8))
                .setExpiration(exp);
        return builder.compact();
    }

    /**
     * 解析 JWT token
     * @param secretKey 签名密钥
     * @param token     token
     * @return Claims
     */
    public static Claims parseJWT(String secretKey, String token) {
        return Jwts.parser()
                .setSigningKey(secretKey.getBytes(StandardCharsets.UTF_8))
                .parseClaimsJws(token)
                .getBody();
    }
}
