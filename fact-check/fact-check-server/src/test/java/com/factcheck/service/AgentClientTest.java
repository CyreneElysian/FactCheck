package com.factcheck.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.factcheck.properties.AgentProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/**
 * {@link AgentClient} 的请求头与重试行为测试（P2-21 / P5-59）
 *
 * <p>用 JDK 自带的 {@code HttpServer} 起一个**真实的本地 HTTP 服务**来接收请求，
 * 而不是 mock RestTemplate——这样验证的是"实际发出的报文里到底有没有这个头"，
 * 也顺带覆盖了 JSON 序列化与响应解析。仅监听 127.0.0.1，不访问外网。</p>
 *
 * <p>背景：Python 侧对 /detect、/dedup、/index/add 做 fail-closed 校验，
 * 而早期 Java 侧根本不发这个头，导致这层鉴权从未生效。</p>
 */
class AgentClientTest {

    private HttpServer server;
    private int port;
    private final List<String> receivedTokens = new ArrayList<>();
    private final List<String> receivedBodies = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        receivedTokens.add(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
        receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] body = "{\"matched\":true,\"record_id\":7,\"score\":0.99,\"matched_text\":\"历史消息\"}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private AgentClient clientWith(String internalToken) {
        AgentProperties properties = new AgentProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setTimeout(5);
        properties.setInternalToken(internalToken);
        return new AgentClient(properties);
    }

    @Test
    @DisplayName("配置了 internal-token 时，请求必须带 X-Internal-Token 头")
    void shouldSendInternalTokenHeader() {
        AgentClient client = clientWith("secret-token");

        AgentClient.DedupResult result = client.dedup("武汉又出现新型病毒了，大家不要出门");

        assertThat(receivedTokens).containsExactly("secret-token");
        // 顺带确认响应解析仍然正常
        assertThat(result.isMatched()).isTrue();
        assertThat(result.getRecordId()).isEqualTo(7L);
        assertThat(result.getMatchedText()).isEqualTo("历史消息");
        assertThat(receivedBodies.get(0)).contains("武汉又出现新型病毒了");
    }

    @Test
    @DisplayName("未配置令牌时不发送该请求头（仅告警，不阻断本地联调）")
    void shouldNotSendHeaderWhenTokenMissing() {
        AgentClient client = clientWith(null);

        client.dedup("武汉又出现新型病毒了，大家不要出门");

        assertThat(receivedTokens).containsExactly((String) null);
    }

    @Test
    @DisplayName("空字符串令牌等同未配置（避免发出一个空头把 Agent 的校验搞混）")
    void shouldTreatBlankTokenAsMissing() {
        AgentClient client = clientWith("   ");

        client.dedup("武汉又出现新型病毒了，大家不要出门");

        assertThat(receivedTokens).containsExactly((String) null);
    }

    @Test
    @DisplayName("indexAdd 同样带令牌（三个内部接口走同一条 post 通道）")
    void shouldSendTokenForIndexAdd() {
        AgentClient client = clientWith("secret-token");

        client.indexAdd(42L, "武汉又出现新型病毒了");

        assertThat(receivedTokens).containsExactly("secret-token");
        assertThat(receivedBodies.get(0)).contains("\"record_id\":42");
    }

    // ------------------------------------------------------------------
    // 连接失败重试（P5-59）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("只读接口遇连接失败会重试一次（Agent 刚重启、端口短暂拒绝连接的场景）")
    void shouldRetryOnceForReadOnlyCall() throws Exception {
        // 先关掉服务，制造连接失败
        server.stop(0);
        AgentProperties properties = new AgentProperties();
        properties.setBaseUrl("http://127.0.0.1:" + port);
        properties.setTimeout(2);
        AgentClient client = new AgentClient(properties);

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> client.dedup("武汉又出现新型病毒了"))
                .isInstanceOf(ResourceAccessException.class);
        long elapsed = System.currentTimeMillis() - start;

        // 两次尝试之间至少有退避时间（500ms），据此确认确实重试过而不是一次就放弃
        assertThat(elapsed).isGreaterThanOrEqualTo(450L);
    }

    @Test
    @DisplayName("有副作用的 indexAdd 不重试（超时后重试可能写入重复向量）")
    void shouldNotRetryForIndexAdd() throws Exception {
        server.stop(0);
        AgentProperties properties = new AgentProperties();
        properties.setBaseUrl("http://127.0.0.1:" + port);
        properties.setTimeout(2);
        AgentClient client = new AgentClient(properties);

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> client.indexAdd(1L, "内容"))
                .isInstanceOf(ResourceAccessException.class);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(450L);
    }
}
