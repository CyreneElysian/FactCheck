package com.factcheck.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.factcheck.constant.JwtClaimsConstant;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link JwtUtil} 单元测试。
 *
 * <p>锁定的是登录/鉴权的信任边界：token 必须能被自己签发、被自己解析回原始 claims；
 * 换密钥、篡改内容、过期三种情况都必须解析失败。
 * 拦截器（JwtTokenUserInterceptor / JwtTokenAdminInterceptor）完全依赖这里的失败语义，
 * 一旦 JwtUtil 变成“解析失败也返回 claims”，鉴权就形同虚设。</p>
 *
 * <p>本测试完全离线：不启动 Spring，不访问网络，纯粹走 jjwt 的签发/解析。</p>
 */
class JwtUtilTest {

    /** 测试用密钥，长度足够 HS256 使用（0.12.x 起会强制校验长度 ≥32 字节） */
    private static final String SECRET = "fact-check-unit-test-secret-key-0123456789";

    /** 另一把密钥，用于验证“换密钥必然解析失败” */
    private static final String WRONG_SECRET = "another-wrong-secret-key-9876543210";

    private static final long ONE_HOUR_MILLIS = 60 * 60 * 1000L;

    /** 构造标准 claims：userId + role，与 JwtClaimsConstant 中定义的 key 一致 */
    private static Map<String, Object> claims(Long userId, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID, userId);
        claims.put(JwtClaimsConstant.ROLE, role);
        return claims;
    }

    /**
     * 签发 → 解析 往返：userId 与 role 必须原样取回，且过期时间存在。
     */
    @Test
    @DisplayName("生成 + 解析往返：userId/role 能原样取回，且携带过期时间")
    void shouldRoundTripClaims() {
        String token = JwtUtil.createJWT(SECRET, ONE_HOUR_MILLIS, claims(12345L, "admin"));

        assertThat(token).isNotBlank();
        // JWT 固定三段：header.payload.signature
        assertThat(token.split("\\.")).hasSize(3);

        Claims parsed = JwtUtil.parseJWT(SECRET, token);

        assertThat(String.valueOf(parsed.get(JwtClaimsConstant.USER_ID))).isEqualTo("12345");
        assertThat(parsed.get(JwtClaimsConstant.ROLE)).isEqualTo("admin");
        assertThat(parsed.getExpiration()).isNotNull().isAfter(new Date());
    }

    /**
     * 用错误密钥解析必须失败（签名校验不通过）。
     *
     * <p>P5-71 升级 jjwt 0.12.5 后，异常类型从 {@code io.jsonwebtoken.SignatureException}
     * 移到了 {@code io.jsonwebtoken.security.SignatureException}。</p>
     */
    @Test
    @DisplayName("用错误密钥解析抛 SignatureException")
    void shouldFailWhenParsedWithWrongSecret() {
        String token = JwtUtil.createJWT(SECRET, ONE_HOUR_MILLIS, claims(1L, "user"));

        SignatureException ex = assertThrows(SignatureException.class,
                () -> JwtUtil.parseJWT(WRONG_SECRET, token));

        assertThat(ex).isInstanceOf(JwtException.class);
    }

    /**
     * 密钥过短必须**明确失败**（P5-71 的升级收益）
     *
     * <p>jjwt 0.9.1 对 HS256 密钥长度不做校验，随手写 "abc" 也能签出 token；
     * 0.12.x 强制 ≥256 位。我们在 {@code JwtUtil} 里把它转成可读的提示，
     * 避免部署时用弱密钥还以为一切正常。</p>
     */
    @Test
    @DisplayName("密钥过短（<32 字节）→ 抛出可读异常，而不是签出一个弱签名 token")
    void shouldRejectWeakSecret() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JwtUtil.createJWT("short", ONE_HOUR_MILLIS, claims(1L, "user")));

        assertThat(ex.getMessage()).contains("JWT 密钥过短").contains("32");
    }

    @Test
    @DisplayName("解析时同样拒绝弱密钥")
    void shouldRejectWeakSecretOnParse() {
        String token = JwtUtil.createJWT(SECRET, ONE_HOUR_MILLIS, claims(1L, "user"));

        assertThrows(IllegalArgumentException.class, () -> JwtUtil.parseJWT("short", token));
    }

    /**
     * 签名算法必须**固定为 HS256**（P5-71）
     *
     * <p>jjwt 0.12 的 {@code signWith(key)} 会按密钥长度自动选算法（64 字节 → HS512），
     * 那样签名算法就随密钥长度漂移，既偏离文档约定，也让"换个更长的密钥"变成破坏性变更。
     * 这条用例直接从 token 头部读 {@code alg} 来锁定。</p>
     */
    @Test
    @DisplayName("签名算法固定为 HS256（不随密钥长度漂移）")
    void shouldAlwaysSignWithHs256() throws Exception {
        String token = JwtUtil.createJWT(SECRET, ONE_HOUR_MILLIS, claims(1L, "user"));

        String headerJson = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[0]),
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(headerJson).contains("\"alg\":\"HS256\"");
    }

    /**
     * 篡改后的 token 必须解析失败。
     * 手法：把合法 token 的 payload 段替换成另一个同样合法的 payload（签名随之为旧签名），
     * 于是“签名与内容不一致”，必须被拒绝——否则攻击者可以随意改 userId 提权。
     */
    @Test
    @DisplayName("篡改 payload 后的 token 解析失败（签名与内容不一致）")
    void shouldFailWhenPayloadTampered() {
        String tokenA = JwtUtil.createJWT(SECRET, ONE_HOUR_MILLIS, claims(1L, "user"));
        String tokenB = JwtUtil.createJWT(SECRET, ONE_HOUR_MILLIS, claims(999L, "admin"));

        String[] partsA = tokenA.split("\\.");
        String[] partsB = tokenB.split("\\.");
        String tampered = partsA[0] + "." + partsB[1] + "." + partsA[2];
        assertThat(tampered).isNotEqualTo(tokenA);

        assertThrows(JwtException.class, () -> JwtUtil.parseJWT(SECRET, tampered));
    }

    /**
     * 结构上根本不是 JWT 的字符串必须是 malformed 失败，而不是被静默当成空 claims。
     */
    @Test
    @DisplayName("非 JWT 结构字符串解析抛 MalformedJwtException")
    void shouldFailWhenTokenIsMalformed() {
        assertThrows(MalformedJwtException.class, () -> JwtUtil.parseJWT(SECRET, "not-a-jwt-at-all"));
    }

    /**
     * 过期 token（ttl 为负数，签发即过期）必须解析失败。
     */
    @Test
    @DisplayName("过期 token（ttl = -1000ms）解析抛 ExpiredJwtException")
    void shouldFailWhenTokenAlreadyExpired() {
        String expired = JwtUtil.createJWT(SECRET, -1000L, claims(1L, "user"));

        assertThrows(ExpiredJwtException.class, () -> JwtUtil.parseJWT(SECRET, expired));
    }

    /**
     * 极短 ttl 场景：签发后稍等即过期，同样必须解析失败。
     * 这条覆盖“过期不是靠本地时钟取整侥幸生效”的情况。
     */
    @Test
    @DisplayName("极短 ttl（1ms）签发后稍等即过期，解析抛 ExpiredJwtException")
    void shouldFailWhenShortLivedTokenExpires() throws InterruptedException {
        String token = JwtUtil.createJWT(SECRET, 1L, claims(1L, "user"));

        Thread.sleep(50L);

        assertThrows(ExpiredJwtException.class, () -> JwtUtil.parseJWT(SECRET, token));
    }
}
