package com.factcheck.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具类，负责 token 的生成与解析
 *
 * <p>P5-71：从 jjwt 0.9.1 升级到 0.12.5。除了 API 现代化，这次升级带来一个**真实的安全收益**：
 * 0.9.1 对 HS256 的密钥长度不做校验，随手写个短字符串也能签出 token；
 * 0.12.x 会强制要求 HMAC 密钥长度足够（≥ 256 位），过短的密钥会在签发/解析时直接抛
 * {@code WeakKeyException}，把"密钥太弱"从运行期隐患变成启动即暴露的问题。</p>
 *
 * <p>注意：密钥长度要求意味着 `application.yml` 里的默认密钥必须是足够长的随机串
 * （当前为 64 位十六进制字符 = 512 位），改成短字符串会直接失败。</p>
 */
public class JwtUtil {

    /**
     * HMAC-SHA 密钥最小字节数（HS256 要求 ≥ 256 位 = 32 字节）
     */
    static final int MIN_KEY_BYTES = 32;

    /**
     * 生成 JWT token
     *
     * <p>注意：jjwt 会把 {@code claims} 包进内部 map 并在设置过期时间时**就地写入**，
     * 因此这里先复制一份——否则调用方传不可变 Map（如 {@code Map.of(...)}）会抛
     * {@code UnsupportedOperationException}。这个问题由 P5-58 的 WebSocket 测试暴露。</p>
     *
     * @param secretKey 签名密钥（UTF-8 字节数需 ≥ 32）
     * @param ttlMillis 过期时间（毫秒）
     * @param claims    自定义声明（不会被修改）
     * @return token
     */
    public static String createJWT(String secretKey, long ttlMillis, Map<String, Object> claims) {
        Date expiration = new Date(System.currentTimeMillis() + ttlMillis);
        return Jwts.builder()
                .claims(claims == null ? new HashMap<>() : new HashMap<>(claims))
                .expiration(expiration)
                // 必须显式指定算法（P5-71）：jjwt 0.12 的 signWith(key) 会**按密钥长度自动选择**
                // （64 字节密钥下会选 HS512），这会静默偏离文档约定的 HS256——
                // 本次升级就是这样被端到端测试发现的：手工按 HS256 签的 token 验签失败。
                .signWith(hmacKey(secretKey), Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 解析 JWT token
     *
     * @param secretKey 签名密钥
     * @param token     token
     * @return Claims
     * @throws io.jsonwebtoken.JwtException 签名不匹配、格式非法或已过期
     */
    public static Claims parseJWT(String secretKey, String token) {
        return Jwts.parser()
                .verifyWith(hmacKey(secretKey))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 由密钥字符串构造 HMAC 密钥
     *
     * <p>把长度校验提前到这里并给出可读的错误信息：0.12.x 抛出的 WeakKeyException
     * 文案较底层，配置写错时不容易看出是"JWT 密钥太短"。</p>
     */
    private static SecretKey hmacKey(String secretKey) {
        byte[] bytes = secretKey == null ? new byte[0] : secretKey.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "JWT 密钥过短：至少需要 " + MIN_KEY_BYTES + " 字节（HS256 要求 ≥256 位），当前 "
                            + bytes.length + " 字节。请检查 jwt.user-secret-key / jwt.admin-secret-key 配置");
        }
        return Keys.hmacShaKeyFor(bytes);
    }
}
