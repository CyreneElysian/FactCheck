package com.factcheck.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * {@link ClientIpUtils} 客户端 IP 解析测试（P2-16）
 *
 * <p>核心风险有两个：</p>
 * <ol>
 *   <li>生产是 nginx → Java，只取 {@code getRemoteAddr()} 会永远拿到 nginx 的地址，
 *       按 IP 限流就形同虚设；</li>
 *   <li>{@code X-Forwarded-For} 是**客户端可伪造**的请求头，
 *       若不校验就拼进 Redis key，攻击者可用任意值制造海量 key 把内存打满。</li>
 * </ol>
 * 因此这两条都要锁住。
 */
class ClientIpUtilsTest {

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest();
    }

    @Test
    @DisplayName("优先取 X-Forwarded-For 的第一个地址（真实客户端），而不是代理地址")
    void shouldPreferFirstForwardedAddress() {
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1, 10.0.0.2");
        request.setRemoteAddr("10.0.0.1");

        assertThat(ClientIpUtils.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("X-Forwarded-For 含伪造的非法值时跳过它，回退到 X-Real-IP")
    void shouldSkipInvalidForwardedValue() {
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "not-an-ip, 203.0.113.9");
        request.addHeader("X-Real-IP", "198.51.100.5");

        assertThat(ClientIpUtils.resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("没有透传头时回退到 remoteAddr")
    void shouldFallbackToRemoteAddr() {
        MockHttpServletRequest request = request();
        request.setRemoteAddr("192.0.2.10");

        assertThat(ClientIpUtils.resolve(request)).isEqualTo("192.0.2.10");
    }

    @ParameterizedTest(name = "[{index}] XFF={0}")
    @DisplayName("伪造的非 IP 值（含超长、含特殊字符）一律不接受，避免制造海量 Redis key")
    @ValueSource(strings = {
            "evil",
            "'; DROP TABLE",
            "a.very.long.hostname.that.is.not.an.ip.address.at.all.example.com",
            "unknown",
            "999.999.999.999",
    })
    void shouldRejectForgedHeaderValues(String forged) {
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", forged);
        request.setRemoteAddr("192.0.2.10");

        assertThat(ClientIpUtils.resolve(request))
                .as("伪造值必须被丢弃并回退到 remoteAddr")
                .isEqualTo("192.0.2.10");
    }

    @Test
    @DisplayName("支持 IPv6（含 :: 压缩写法）")
    void shouldSupportIpv6() {
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "2001:db8::1");

        assertThat(ClientIpUtils.resolve(request)).isEqualTo("2001:db8::1");

        MockHttpServletRequest loopback = request();
        loopback.addHeader("X-Real-IP", "::1");
        assertThat(ClientIpUtils.resolve(loopback)).isEqualTo("::1");
    }

    @Test
    @DisplayName("不在 HTTP 请求上下文中时 currentIp() 返回 null（调用方跳过 IP 维度）")
    void shouldReturnNullOutsideRequestContext() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();

        assertThat(ClientIpUtils.currentIp()).isNull();
    }

    @Test
    @DisplayName("在请求上下文中时 currentIp() 能取到值")
    void shouldReadCurrentRequestContext() {
        MockHttpServletRequest request = request();
        request.addHeader("X-Real-IP", "203.0.113.11");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            assertThat(ClientIpUtils.currentIp()).isEqualTo("203.0.113.11");
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }
}
