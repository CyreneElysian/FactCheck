package com.factcheck.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.factcheck.constant.MessageConstant;
import com.factcheck.dto.UserLoginDTO;
import com.factcheck.entity.User;
import com.factcheck.exception.RateLimitException;
import com.factcheck.mapper.UserMapper;
import com.factcheck.properties.RateLimitProperties;
import com.factcheck.properties.WeChatProperties;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 登录限流的**链路级**测试：真实 {@link RateLimitServiceImpl} + 有状态的内存计数器
 *
 * <p>与只验证"是否被调用"的用法不同，这里把真实的限流实现接到登录流程上，
 * 用一个内存 Map 模拟 Redis 的 INCR 语义，从而真正跑通
 * "连续失败 → 达到阈值 → 第 N+1 次被拒"这条链路（本机没有 Redis/MySQL，无法做 HTTP 端到端，
 * 这是在不依赖外部服务的条件下最接近真实的验证方式）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoginRateLimitIntegrationTest {

    private static final String PASSWORD = "secret123";
    private static final String HASH = new BCryptPasswordEncoder().encode(PASSWORD);
    private static final String CLIENT_IP = "203.0.113.7";

    @Mock
    private UserMapper userMapper;

    @Mock
    private WeChatProperties weChatProperties;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private UserServiceImpl userService;

    private final RateLimitProperties properties = new RateLimitProperties();

    /**
     * 模拟 Redis 的计数状态
     */
    private final Map<String, Long> counters = new HashMap<>();

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString()))
                .thenAnswer(invocation -> counters.merge(invocation.getArgument(0), 1L, Long::sum));

        RateLimitServiceImpl rateLimitService = new RateLimitServiceImpl();
        ReflectionTestUtils.setField(rateLimitService, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(rateLimitService, "properties", properties);

        userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "userMapper", userMapper);
        ReflectionTestUtils.setField(userService, "weChatProperties", weChatProperties);
        ReflectionTestUtils.setField(userService, "rateLimitService", rateLimitService);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", CLIENT_IP);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private UserLoginDTO login(String username, String password) {
        UserLoginDTO dto = new UserLoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        return dto;
    }

    private User user() {
        return User.builder().id(1L).username("alice").password(HASH).status(1).build();
    }

    @Test
    @DisplayName("同一账号连续失败 5 次后，第 6 次被限流拒绝（默认阈值 5 次/10 分钟）")
    void shouldBlockAfterFiveFailedAttempts() {
        when(userMapper.getByUsername("alice")).thenReturn(user());

        // 前 5 次：正常走完流程（密码错误 → PasswordErrorException）
        for (int i = 1; i <= 5; i++) {
            int attempt = i;
            assertThatThrownBy(() -> userService.login(login("alice", "wrong-password")))
                    .as("第 %d 次尝试不应被限流（阈值 5）", attempt)
                    .isNotInstanceOf(RateLimitException.class);
        }

        // 第 6 次：限流生效
        assertThatThrownBy(() -> userService.login(login("alice", "wrong-password")))
                .isInstanceOf(RateLimitException.class)
                .hasMessage(MessageConstant.RATE_LIMIT);

        assertThat(counters).containsKey("fact:limit:login:user:alice");
    }

    @Test
    @DisplayName("被限流后即使密码正确也会被拒（限流在密码校验之前）")
    void shouldRejectEvenWithCorrectPasswordAfterLimit() {
        when(userMapper.getByUsername("alice")).thenReturn(user());

        for (int i = 1; i <= 5; i++) {
            assertThatThrownBy(() -> userService.login(login("alice", "wrong-password")));
        }

        assertThatThrownBy(() -> userService.login(login("alice", PASSWORD)))
                .isInstanceOf(RateLimitException.class);
    }

    @Test
    @DisplayName("按用户名计数：换账号不受影响（Alice 被限流不影响 bob）")
    void shouldCountPerUsername() {
        when(userMapper.getByUsername(anyString())).thenReturn(null); // 用户不存在 → 登录失败

        for (int i = 1; i <= 5; i++) {
            assertThatThrownBy(() -> userService.login(login("alice", "x")));
        }
        assertThatThrownBy(() -> userService.login(login("alice", "x")))
                .isInstanceOf(RateLimitException.class);

        // bob 是第一次尝试，不该被 alice 的计数影响
        assertThatThrownBy(() -> userService.login(login("bob", "x")))
                .as("按用户名限流不应误伤其他账号")
                .isNotInstanceOf(RateLimitException.class);
    }

    @Test
    @DisplayName("按 IP 计数：多个账号依次尝试，累计到 20 次后该 IP 被拦（防批量撞库）")
    void shouldBlockByIpAfterManyAttemptsAcrossAccounts() {
        when(userMapper.getByUsername(anyString())).thenReturn(null);

        for (int i = 1; i <= 20; i++) {
            String username = "user" + i;
            int attempt = i;
            assertThatThrownBy(() -> userService.login(login(username, "x")))
                    .as("第 %d 次（不同账号）不应触发按用户名的限流", attempt)
                    .isNotInstanceOf(RateLimitException.class);
        }

        assertThatThrownBy(() -> userService.login(login("user21", "x")))
                .as("累计 20 次后应按 IP 拦截")
                .isInstanceOf(RateLimitException.class)
                .hasMessage(MessageConstant.RATE_LIMIT);

        assertThat(counters).containsKey("fact:limit:login:ip:" + CLIENT_IP);
    }

    @Test
    @DisplayName("用户名大小写变体共用同一个计数桶（MySQL 唯一索引大小写不敏感）")
    void shouldShareCounterAcrossUsernameCase() {
        when(userMapper.getByUsername(anyString())).thenReturn(null);

        for (int i = 1; i <= 5; i++) {
            assertThatThrownBy(() -> userService.login(login("Alice", "x")));
        }

        // 换成全小写继续尝试，仍应被拦（否则改大小写即可绕过）
        assertThatThrownBy(() -> userService.login(login("alice", "x")))
                .isInstanceOf(RateLimitException.class);
    }

    @Test
    @DisplayName("限流关闭开关生效（enabled=false 时连续尝试不被拦）")
    void shouldRespectDisabledSwitch() {
        properties.setEnabled(false);
        when(userMapper.getByUsername("alice")).thenReturn(user());

        for (int i = 1; i <= 10; i++) {
            assertThatThrownBy(() -> userService.login(login("alice", "wrong-password")))
                    .isNotInstanceOf(RateLimitException.class);
        }
        assertThat(counters).isEmpty();
    }

    @Test
    @DisplayName("Redis 不可用时登录仍能正常进行（fail-open）")
    void shouldFailOpenWhenRedisUnavailable() {
        when(valueOperations.increment(anyString())).thenThrow(new RuntimeException("Redis 挂了"));
        when(userMapper.getByUsername("alice")).thenReturn(user());

        // 密码正确 → 应正常登录成功，而不是因为限流组件故障被拒
        User logged = userService.login(login("alice", PASSWORD));

        assertThat(logged.getId()).isEqualTo(1L);
    }
}
