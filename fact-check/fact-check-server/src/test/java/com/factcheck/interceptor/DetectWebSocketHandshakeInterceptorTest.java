package com.factcheck.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.factcheck.context.BaseContext;
import com.factcheck.entity.FactCheckRecord;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.properties.JwtProperties;
import com.factcheck.util.JwtUtil;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

/**
 * WebSocket 握手鉴权与 ThreadLocal 清理测试（P5-58 / P5-48）
 *
 * <p>修复前的问题：任何人都能连 {@code /ws/detect?recordId=N} 并实时收到**别人**的检测结果
 * （结论、置信度、推理链）；同时两个 JWT 拦截器从不清理 ThreadLocal，
 * Tomcat 线程复用会让下一个请求读到上一个请求的身份。</p>
 *
 * <p>完全离线：Mapper 与 JwtProperties 都是 mock；握手请求用
 * {@link ServletServerHttpRequest} 包装 {@link MockHttpServletRequest} 构造
 * （拦截器签名要的是 servlet 版 {@code ServerHttpRequest}，不是 reactive 版）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DetectWebSocketHandshakeInterceptorTest {

    private static final String SECRET = "test-user-secret-key-for-handshake";
    private static final Long OWNER_ID = 11L;

    @Mock
    private FactCheckRecordMapper factCheckRecordMapper;

    @Mock
    private JwtProperties jwtProperties;

    @Mock
    private WebSocketHandler wsHandler;

    @InjectMocks
    private DetectWebSocketHandshakeInterceptor interceptor;

    private final ServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());

    @BeforeEach
    void setUp() {
        when(jwtProperties.getUserSecretKey()).thenReturn(SECRET);
    }

    @AfterEach
    void clearContext() {
        BaseContext.removeCurrentId();
    }

    private String tokenOf(Long userId) {
        return JwtUtil.createJWT(SECRET, 3600_000L, Map.of("userId", userId));
    }

    private ServerHttpRequest request(String query) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/api/ws/detect");
        servletRequest.setQueryString(query);
        return new ServletServerHttpRequest(servletRequest);
    }

    private boolean handshake(String query, Map<String, Object> attributes) {
        return interceptor.beforeHandshake(request(query), response, wsHandler, attributes);
    }

    @Test
    @DisplayName("缺少 recordId → 拒绝握手")
    void shouldRejectWithoutRecordId() {
        assertThat(handshake("", new HashMap<>())).isFalse();
        assertThat(handshake("recordId=", new HashMap<>())).isFalse();
    }

    @Test
    @DisplayName("recordId 非法（非数字）→ 拒绝握手")
    void shouldRejectInvalidRecordId() {
        assertThat(handshake("recordId=abc", new HashMap<>())).isFalse();
    }

    @Test
    @DisplayName("缺少 token → 拒绝握手（修复前无此校验，任何人可订阅）")
    void shouldRejectWithoutToken() {
        assertThat(handshake("recordId=1", new HashMap<>())).isFalse();
    }

    @Test
    @DisplayName("token 无效（用别的密钥签发）→ 拒绝握手")
    void shouldRejectInvalidToken() {
        // 注意：伪造用的密钥同样要 ≥32 字节，否则 jjwt 0.12 会因"密钥过短"直接拒绝签发
        // （这正说明升级后弱密钥无法蒙混过关）
        String forged = JwtUtil.createJWT("another-secret-key-for-forgery-0123456789",
                3600_000L, Map.of("userId", OWNER_ID));

        assertThat(handshake("recordId=1&token=" + forged, new HashMap<>())).isFalse();
    }

    @Test
    @DisplayName("越权订阅：记录属于别人 → 拒绝握手")
    void shouldRejectWhenRecordBelongsToAnotherUser() {
        when(factCheckRecordMapper.getById(1L))
                .thenReturn(FactCheckRecord.builder().id(1L).userId(99L).build());

        assertThat(handshake("recordId=1&token=" + tokenOf(OWNER_ID), new HashMap<>())).isFalse();
    }

    @Test
    @DisplayName("记录不存在 → 拒绝握手")
    void shouldRejectWhenRecordMissing() {
        when(factCheckRecordMapper.getById(1L)).thenReturn(null);

        assertThat(handshake("recordId=1&token=" + tokenOf(OWNER_ID), new HashMap<>())).isFalse();
    }

    @Test
    @DisplayName("本人订阅自己的记录 → 握手通过，并写入 recordId 与 userId")
    void shouldAcceptOwnerSubscription() {
        when(factCheckRecordMapper.getById(1L))
                .thenReturn(FactCheckRecord.builder().id(1L).userId(OWNER_ID).build());
        Map<String, Object> attributes = new HashMap<>();

        assertThat(handshake("recordId=1&token=" + tokenOf(OWNER_ID), attributes)).isTrue();

        assertThat(attributes)
                .containsEntry(DetectWebSocketHandshakeInterceptor.ATTR_RECORD_ID, 1L)
                .containsEntry(DetectWebSocketHandshakeInterceptor.ATTR_USER_ID, OWNER_ID);
    }

    @Test
    @DisplayName("用户端拦截器在请求结束后清理 ThreadLocal（P5-48：防线程复用导致身份串号）")
    void shouldClearThreadLocalAfterCompletion() {
        JwtTokenUserInterceptor userInterceptor = new JwtTokenUserInterceptor();
        BaseContext.setCurrentId(123L);

        userInterceptor.afterCompletion(null, null, null, null);

        assertThat(BaseContext.getCurrentId()).isNull();
    }

    @Test
    @DisplayName("管理端拦截器同样清理 ThreadLocal")
    void shouldClearThreadLocalInAdminInterceptor() {
        JwtTokenAdminInterceptor adminInterceptor = new JwtTokenAdminInterceptor();
        BaseContext.setCurrentId(456L);

        adminInterceptor.afterCompletion(null, null, null, null);

        assertThat(BaseContext.getCurrentId()).isNull();
    }
}
