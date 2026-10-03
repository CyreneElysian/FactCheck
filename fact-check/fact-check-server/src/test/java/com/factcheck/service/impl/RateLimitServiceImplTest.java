package com.factcheck.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.factcheck.constant.MessageConstant;
import com.factcheck.constant.RedisKeyConstant;
import com.factcheck.exception.RateLimitException;
import com.factcheck.properties.RateLimitProperties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@link RateLimitServiceImpl} 的计数与降级行为测试（P2-16 / S9）
 *
 * <p>锁定：key 形式、只在首次计数时设过期、超阈值抛限流异常、
 * **Redis 异常时放行（fail-open）**、关闭开关后完全不动作。</p>
 *
 * <p>完全离线：RedisTemplate 全部 mock。</p>
 */
@ExtendWith(MockitoExtension.class)
class RateLimitServiceImplTest {

    private static final String SCENE = "login:user";
    private static final String IDENTITY = "alice";
    private static final String KEY = RedisKeyConstant.RATE_LIMIT_PREFIX + SCENE + ":" + IDENTITY;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private RateLimitServiceImpl rateLimitService;

    private final RateLimitProperties properties = new RateLimitProperties();

    @BeforeEach
    void wireProperties() {
        // @InjectMocks 只注入 mock，配置对象手动放进字段
        org.springframework.test.util.ReflectionTestUtils.setField(rateLimitService, "properties", properties);
    }

    @Test
    @DisplayName("首次计数：写入 key 并设置窗口过期")
    void shouldSetExpireOnFirstCount() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(KEY)).thenReturn(1L);

        rateLimitService.check(SCENE, IDENTITY, 5, 600);

        verify(stringRedisTemplate).expire(KEY, 600L, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("窗口内未超阈值：不设置过期、不抛异常")
    void shouldNotResetExpireWithinWindow() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(KEY)).thenReturn(3L);

        rateLimitService.check(SCENE, IDENTITY, 5, 600);

        // 每次请求都刷新过期会变成"滑动窗口"，高频请求下计数永不过期
        verify(stringRedisTemplate, never()).expire(eq(KEY), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("超过阈值：抛 RateLimitException（文案为“操作过于频繁”）")
    void shouldThrowWhenOverLimit() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(KEY)).thenReturn(6L);

        assertThatThrownBy(() -> rateLimitService.check(SCENE, IDENTITY, 5, 600))
                .isInstanceOf(RateLimitException.class)
                .hasMessage(MessageConstant.RATE_LIMIT);
    }

    @Test
    @DisplayName("正好等于阈值时放行（边界闭区间）")
    void shouldAllowAtExactLimit() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(KEY)).thenReturn(5L);

        rateLimitService.check(SCENE, IDENTITY, 5, 600);
    }

    @Test
    @DisplayName("Redis 异常 → fail-open（记日志放行，不抛异常）")
    void shouldFailOpenWhenRedisUnavailable() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(KEY)).thenThrow(new RuntimeException("Redis 连接失败"));

        rateLimitService.check(SCENE, IDENTITY, 5, 600);
    }

    @Test
    @DisplayName("enabled=false → 完全不访问 Redis")
    void shouldSkipWhenDisabled() {
        properties.setEnabled(false);

        rateLimitService.check(SCENE, IDENTITY, 5, 600);

        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("身份为空 / 阈值非法 → 直接放行（不产生无意义的 key）")
    void shouldSkipWhenIdentityMissing() {
        rateLimitService.check(SCENE, null, 5, 600);
        rateLimitService.check(SCENE, "  ", 5, 600);
        rateLimitService.check(SCENE, IDENTITY, 0, 600);

        verify(stringRedisTemplate, never()).opsForValue();
    }

    // ------------------------------------------------------------------
    // 三个场景的 key 与阈值
    // ------------------------------------------------------------------

    @Test
    @DisplayName("检测提交：key 与旧实现一致（fact:limit:detect:{userId}），窗口一天")
    void shouldBuildDetectKeyCompatibleWithOldImplementation() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(RedisKeyConstant.DETECT_LIMIT_PREFIX + 12)).thenReturn(1L);

        rateLimitService.checkDetectSubmit(12L);

        verify(stringRedisTemplate).expire(RedisKeyConstant.DETECT_LIMIT_PREFIX + 12,
                TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("登录：按用户名与按 IP 各计一次；用户名统一小写（MySQL 唯一索引大小写不敏感，否则可用大小写绕过）")
    void shouldCheckBothDimensionsForLogin() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);

        rateLimitService.checkLogin("  Alice  ", "203.0.113.7");

        verify(valueOperations).increment(RedisKeyConstant.RATE_LIMIT_PREFIX + "login:user:alice");
        verify(valueOperations).increment(RedisKeyConstant.RATE_LIMIT_PREFIX + "login:ip:203.0.113.7");
    }

    @Test
    @DisplayName("登录：拿不到 IP 时只按用户名限流，不因缺 IP 而放行")
    void shouldStillLimitByUsernameWhenIpMissing() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);

        rateLimitService.checkLogin("alice", null);

        verify(valueOperations).increment(RedisKeyConstant.RATE_LIMIT_PREFIX + "login:user:alice");
        verify(valueOperations, never()).increment(RedisKeyConstant.RATE_LIMIT_PREFIX + "login:ip:null");
    }

    @Test
    @DisplayName("注册：按 IP 计数")
    void shouldLimitRegisterByIp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);

        rateLimitService.checkRegister("203.0.113.7");

        verify(valueOperations).increment(RedisKeyConstant.RATE_LIMIT_PREFIX + "register:ip:203.0.113.7");
    }

    @Test
    @DisplayName("超长身份被截断，避免伪造超长用户名/IP 造出巨型 key")
    void shouldTruncateOverlongIdentity() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);

        rateLimitService.check(SCENE, "x".repeat(500), 5, 600);

        verify(valueOperations).increment(RedisKeyConstant.RATE_LIMIT_PREFIX + SCENE + ":" + "x".repeat(64));
    }
}
