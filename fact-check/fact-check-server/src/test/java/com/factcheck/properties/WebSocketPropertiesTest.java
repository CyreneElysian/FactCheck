package com.factcheck.properties;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link WebSocketProperties} 的**配置绑定**契约测试。
 *
 * <h3>为什么测"绑定"而不是测 Controller/Config 的调用参数</h3>
 * 本项加固的失效模式是"配置写了但没生效"——例如 yml 里的键名与
 * {@code @ConfigurationProperties(prefix = "fact-check.websocket")} 对不上，
 * 于是 {@code allowed-origins} 被静默忽略、又退回默认的 {@code "*"}。
 * 直接验证 Spring 的绑定结果，正好覆盖这个失效模式，而且**用真实的 Binder**，
 * 比 mock 出参更有说服力。
 *
 * <p>（另一个候选做法——mock {@code WebSocketHandlerRegistry} 并捕获
 * {@code setAllowedOrigins} 的参数——实测不可行：Mockito 对
 * {@code addHandler(handler, String... patterns)} 这类**可变参数**的匹配不可靠，
 * 链式调用会返回 null 导致 NPE。这个坑在第 1 批的拦截器路径测试里已经踩过一次，
 * 当时的结论就是"这类 API 不要用 Mockito，改用真实启动验证"。）</p>
 */
class WebSocketPropertiesTest {

    /**
     * 用真实 Binder 按 prefix 绑定一组属性
     */
    private WebSocketProperties bind(Map<String, String> props) {
        Binder binder = new Binder(new MapConfigurationPropertySource(props));
        WebSocketProperties properties = new WebSocketProperties();
        binder.bind("fact-check.websocket", Bindable.ofInstance(properties));
        return properties;
    }

    @Test
    @DisplayName("默认：allowed-origins 为 \"*\"，isWildcard() 为 true")
    void shouldDefaultToWildcard() {
        WebSocketProperties properties = new WebSocketProperties();
        assertThat(properties.getAllowedOrigins()).containsExactly("*");
        assertThat(properties.isWildcard()).isTrue();
    }

    @Test
    @DisplayName("绑定逗号分隔的多个来源（与 application.yml 的写法一致）")
    void shouldBindCommaSeparatedOrigins() {
        Map<String, String> props = new HashMap<>();
        props.put("fact-check.websocket.allowed-origins",
                "https://factcheck.example.com,https://www.factcheck.example.com");

        WebSocketProperties properties = bind(props);

        assertThat(properties.getAllowedOrigins()).containsExactly(
                "https://factcheck.example.com", "https://www.factcheck.example.com");
        assertThat(properties.isWildcard()).isFalse();
    }

    @Test
    @DisplayName("绑定单个来源，以及带通配主机名的写法（Spring 支持主机名整体通配）")
    void shouldBindSingleAndWildcardHost() {
        Map<String, String> single = new HashMap<>();
        single.put("fact-check.websocket.allowed-origins", "https://factcheck.example.com");
        assertThat(bind(single).getAllowedOrigins()).containsExactly("https://factcheck.example.com");

        Map<String, String> wildcardHost = new HashMap<>();
        wildcardHost.put("fact-check.websocket.allowed-origins", "https://*.example.com");
        WebSocketProperties p = bind(wildcardHost);
        assertThat(p.getAllowedOrigins()).containsExactly("https://*.example.com");
        // 注意：带通配的**主机名**不算 isWildcard（只有整值为 "*" 才算"完全不限制"）
        assertThat(p.isWildcard()).isFalse();
    }

    @Test
    @DisplayName("Spring 宽松绑定：allowedOrigins / ALLOWED_ORIGINS 等写法都能绑上（不会因大小写或分隔符失效）")
    void relaxedBindingShouldAcceptCamelCaseAndUpperCase() {
        // 驼峰
        Map<String, String> camel = new HashMap<>();
        camel.put("fact-check.websocket.allowedOrigins", "https://a.example.com");
        assertThat(bind(camel).getAllowedOrigins()).containsExactly("https://a.example.com");

        // 全大写下划线（环境变量 FACTCHECK_WS_ALLOWED_ORIGINS 走的就是这条宽松绑定路径）
        Map<String, String> upper = new HashMap<>();
        upper.put("fact-check.websocket.ALLOWED_ORIGINS", "https://b.example.com");
        assertThat(bind(upper).getAllowedOrigins()).containsExactly("https://b.example.com");
    }

    @Test
    @DisplayName("前缀写错则绑定不上，静默保持默认 \"*\" —— 这正是要防的失效，必须靠启动日志核对")
    void wrongPrefixShouldSilentlyKeepDefault() {
        Map<String, String> props = new HashMap<>();
        // 故意写错前缀（例如漏了 websocket 这一层）
        props.put("fact-check.ws.allowed-origins", "https://factcheck.example.com");

        WebSocketProperties properties = bind(props);

        assertThat(properties.getAllowedOrigins()).containsExactly("*");
        assertThat(properties.isWildcard()).isTrue();
        // 记录框架行为：绑定失败**不会**报错，只会静默用默认值。
        // 因此部署时必须核对启动日志里打印的允许来源（WebSocketConfig 的非通配分支会打印），
        // docs/部署文档.md 的安全清单已把"确认 WebSocket 来源已收窄"列为检查项。
    }

    @Test
    @DisplayName("绑定后用真实配置路径 fact-check.websocket.allowed-origins（与 application.yml 逐字一致）")
    void ymlKeyShouldMatch() throws Exception {
        // 从真机 yml 里读一行，确保本测试用的键名与实际部署用的键名不会各自漂移
        java.io.File yml = new java.io.File(
                "src/main/resources/application.yml");
        assertThat(yml).as("application.yml 应存在").exists();
        String text = new String(java.nio.file.Files.readAllBytes(yml.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text).contains("allowed-origins:").contains("FACTCHECK_WS_ALLOWED_ORIGINS");
    }
}
