package com.factcheck.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * WebSocket 相关配置（对应 {@code application.yml} 的 {@code fact-check.websocket}）
 *
 * <h3>为什么需要它</h3>
 * {@code WebSocketConfig} 原先硬编码 {@code setAllowedOrigins("*")}：任意站点都能对本服务
 * 发起 WebSocket 握手。虽然本项目的鉴权是"显式携带 token 查询参数"
 * （见 {@code DetectWebSocketHandshakeInterceptor}），**不是** cookie 自动携带，
 * 因此不构成典型的 CSWSH（跨站 WebSocket 劫持）；但放开全部来源仍然意味着：
 * <ul>
 *   <li>任意页面都能拿到"握手是否成功"这一位信息，可用于探测；</li>
 *   <li>一旦将来鉴权方式改为 cookie，就会立刻变成真实的 CSWSH 漏洞——把来源收窄是提前拆除这颗雷；</li>
 *   <li>浏览器直连部署（前端与后端不同源）时需要显式声明允许的来源，配置化后不必改代码。</li>
 * </ul>
 *
 * <h3>为什么默认仍是 {@code *}</h3>
 * 本地开发时前端可能跑在 Vite 的 5173、也可能通过 nginx 的 80/8081 访问，
 * 写死单值会让"克隆下来就能跑"失效。因此默认保持宽容，**但由配置驱动**，
 * 并在启动日志里打印当前生效值——生产部署时按 §部署文档 的清单改成真实域名即可。
 */
@Component
@ConfigurationProperties(prefix = "fact-check.websocket")
@Data
public class WebSocketProperties {

    /**
     * 允许的握手来源（Origin）
     *
     * <p>支持精确值（{@code https://example.com}）与通配写法（{@code https://*.example.com}）；
     * 单个 {@code *} 表示不限制来源（仅建议本地开发使用）。</p>
     *
     * <p>Spring 的匹配规则：{@code *} 只能作为**整个值**或**主机名的整体**出现，
     * 不支持 {@code https://example.com:*} 这类端口通配。</p>
     */
    private List<String> allowedOrigins = new ArrayList<>(Collections.singletonList("*"));

    /**
     * 当前是否处于"不限制来源"状态（供启动日志提示用）
     */
    public boolean isWildcard() {
        return allowedOrigins == null || allowedOrigins.isEmpty()
                || allowedOrigins.contains("*");
    }
}
