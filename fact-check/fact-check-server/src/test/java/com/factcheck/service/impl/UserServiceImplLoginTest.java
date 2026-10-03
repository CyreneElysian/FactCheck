package com.factcheck.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.factcheck.constant.MessageConstant;
import com.factcheck.dto.UserLoginDTO;
import com.factcheck.entity.OperationLog;
import com.factcheck.entity.User;
import com.factcheck.exception.AccountNotFoundException;
import com.factcheck.exception.PasswordErrorException;
import com.factcheck.exception.RateLimitException;
import com.factcheck.mapper.OperationLogMapper;
import com.factcheck.mapper.UserMapper;
import com.factcheck.properties.WeChatProperties;
import com.factcheck.service.RateLimitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 登录防爆破限流的接入测试（P2-16 / S9）
 *
 * <p>锁定三件事：</p>
 * <ol>
 *   <li>登录**一定会**先经过限流（按用户名 + 按 IP 双维度）；</li>
 *   <li>被限流时**不再查库、不做密码比对**（fail fast，避免把数据库和 BCrypt 的算力也送给爆破者）；</li>
 *   <li>客户端 IP 取自反向代理透传的 {@code X-Forwarded-For}（生产是 nginx → Java，
 *       直接取 remoteAddr 只会拿到 nginx 的地址，按 IP 限流就形同虚设）。</li>
 * </ol>
 *
 * <p>完全离线：Mapper/限流服务都是 mock，BCrypt 用真实实现（纯 CPU）。</p>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplLoginTest {

    private static final String CLIENT_IP = "203.0.113.7";

    private static final String RAW_PASSWORD = "secret123";

    /**
     * 用真实编码器生成口令哈希作为夹具
     *
     * <p>注意：不要硬编码别处的哈希——第一版这里抄了 sql 种子里的
     * {@code $2a$10$HMyV...}（那其实是 admin 的 Admin@123456），
     * 结果"登录成功"的用例一直报密码错误。夹具与被测代码共享同一算法最稳妥。</p>
     */
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder().encode(RAW_PASSWORD);

    @Mock
    private UserMapper userMapper;

    @Mock
    private WeChatProperties weChatProperties;

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private OperationLogMapper operationLogMapper;

    @InjectMocks
    private UserServiceImpl userService;

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * 模拟经 nginx 转发过来的请求
     */
    private void stubRequestFromProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", CLIENT_IP + ", 10.0.0.1");
        request.setRemoteAddr("10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private UserLoginDTO dto(String username, String password) {
        UserLoginDTO dto = new UserLoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        return dto;
    }

    private User enabledUser() {
        return User.builder()
                .id(1L)
                .username("alice")
                .password(PASSWORD_HASH)
                .status(1)
                .build();
    }

    @Test
    @DisplayName("登录前按“原始用户名 + 客户端真实 IP”做限流（IP 来自 X-Forwarded-For）")
    void shouldRateLimitByUsernameAndForwardedIp() {
        stubRequestFromProxy();
        when(userMapper.getByUsername("alice")).thenReturn(enabledUser());

        userService.login(dto("alice", RAW_PASSWORD));

        verify(rateLimitService).checkLogin("alice", CLIENT_IP);
    }

    @Test
    @DisplayName("被限流时直接抛出限流异常，且不查库、不比密码")
    void shouldFailFastWhenRateLimited() {
        stubRequestFromProxy();
        doThrow(new RateLimitException(MessageConstant.RATE_LIMIT))
                .when(rateLimitService).checkLogin(anyString(), anyString());

        assertThatThrownBy(() -> userService.login(dto("alice", RAW_PASSWORD)))
                .isInstanceOf(RateLimitException.class)
                .hasMessage(MessageConstant.RATE_LIMIT);

        verify(userMapper, never()).getByUsername(anyString());
    }

    @Test
    @DisplayName("限流在参数校验之前：用户名不合法也照样计数，避免绕过")
    void shouldRateLimitEvenWhenUsernameInvalid() {
        stubRequestFromProxy();
        doThrow(new RateLimitException(MessageConstant.RATE_LIMIT))
                .when(rateLimitService).checkLogin(any(), any());

        assertThatThrownBy(() -> userService.login(dto(null, null)))
                .isInstanceOf(RateLimitException.class);

        verify(rateLimitService).checkLogin(null, CLIENT_IP);
    }

    @Test
    @DisplayName("密码错误仍然抛 PasswordErrorException（限流不改变原有登录语义）")
    void shouldStillRejectWrongPassword() {
        stubRequestFromProxy();
        when(userMapper.getByUsername("alice")).thenReturn(enabledUser());

        assertThatThrownBy(() -> userService.login(dto("alice", "wrong-password")))
                .isInstanceOf(PasswordErrorException.class)
                .hasMessage(MessageConstant.PASSWORD_ERROR);
    }

    @Test
    @DisplayName("密码正确时正常登录并刷新最后登录时间")
    void shouldLoginSuccessfully() {
        stubRequestFromProxy();
        when(userMapper.getByUsername("alice")).thenReturn(enabledUser());

        User user = userService.login(dto("alice", RAW_PASSWORD));

        assertThat(user.getId()).isEqualTo(1L);
        assertThat(user.getLastLoginTime()).isNotNull();
        verify(userMapper).update(any(User.class));
    }

    @Test
    @DisplayName("不在 HTTP 请求上下文中（如定时任务/异步线程）时 IP 为 null，只按用户名限流而不报错")
    void shouldTolerateMissingRequestContext() {
        RequestContextHolder.resetRequestAttributes();
        when(userMapper.getByUsername("alice")).thenReturn(enabledUser());

        userService.login(dto("alice", RAW_PASSWORD));

        verify(rateLimitService).checkLogin("alice", null);
    }

    // ------------------------------------------------------------------
    // 管理员登录留痕（P5-56 / S3）
    // ------------------------------------------------------------------

    private User admin() {
        User admin = enabledUser();
        admin.setRole(1);
        return admin;
    }

    @Test
    @DisplayName("管理员登录成功 → 写一条操作日志（S3 要求留痕）")
    void shouldLogAdminLoginSuccess() {
        stubRequestFromProxy();
        when(userMapper.getByUsername("alice")).thenReturn(admin());

        userService.login(dto("alice", RAW_PASSWORD));

        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(operationLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getAdminId()).isEqualTo(1L);
        assertThat(captor.getValue().getOperation()).isEqualTo("管理员登录");
    }

    @Test
    @DisplayName("管理员密码错误 → 同样留痕（便于核查异常登录），且不影响抛出的业务异常")
    void shouldLogAdminLoginFailure() {
        stubRequestFromProxy();
        when(userMapper.getByUsername("alice")).thenReturn(admin());

        assertThatThrownBy(() -> userService.login(dto("alice", "wrong-password")))
                .isInstanceOf(PasswordErrorException.class);

        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(operationLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getOperation()).contains("失败");
    }

    @Test
    @DisplayName("普通用户登录不写操作日志（避免把审计表灌满）；用户不存在时也不写")
    void shouldNotLogForOrdinaryUser() {
        stubRequestFromProxy();
        when(userMapper.getByUsername("alice")).thenReturn(enabledUser());

        userService.login(dto("alice", RAW_PASSWORD));

        verify(operationLogMapper, never()).insert(any(OperationLog.class));

        // 用户名不存在：同样不写（否则任意用户名都能往审计表写一行）
        when(userMapper.getByUsername("nobody")).thenReturn(null);
        assertThatThrownBy(() -> userService.login(dto("nobody", "x")))
                .isInstanceOf(AccountNotFoundException.class);
        verify(operationLogMapper, never()).insert(any(OperationLog.class));
    }

    @Test
    @DisplayName("审计写入失败不影响登录成功（日志不能反过来阻断业务）")
    void shouldNotBreakLoginWhenAuditFails() {
        stubRequestFromProxy();
        when(userMapper.getByUsername("alice")).thenReturn(admin());
        // insert 返回 void，必须用 doThrow 语法
        doThrow(new RuntimeException("数据库不可用")).when(operationLogMapper).insert(any(OperationLog.class));

        User logged = userService.login(dto("alice", RAW_PASSWORD));

        assertThat(logged.getId()).isEqualTo(1L);
    }
}
