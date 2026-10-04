package com.factcheck.config;

import com.factcheck.handler.DetectWebSocketHandler;
import com.factcheck.interceptor.DetectWebSocketHandshakeInterceptor;
import com.factcheck.properties.WebSocketProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Collections;
import java.util.List;

/**
 * WebSocket 配置
 * 检测结果通过 /api/ws/detect 实时推送给前端
 */
@Configuration
@EnableWebSocket
@Slf4j
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private DetectWebSocketHandler detectWebSocketHandler;

    /**
     * 握手拦截器必须走 Spring 注入（P5-58）
     *
     * <p>早期这里是 {@code new DetectWebSocketHandshakeInterceptor()}：直接 new 出来的对象
     * 拿不到容器里的 JwtProperties 与 Mapper，也就无法做鉴权（任何人可订阅任意记录的结果）。
     * 改为注入 Bean，同时不要再在别处 new 它。</p>
     */
    @Autowired
    private DetectWebSocketHandshakeInterceptor detectWebSocketHandshakeInterceptor;

    @Autowired
    private WebSocketProperties webSocketProperties;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        List<String> origins = webSocketProperties.getAllowedOrigins();
        if (origins == null || origins.isEmpty()) {
            // 兜底：配置被显式清空时按"不限制"处理，避免把功能直接打死。
            // 不用 setAllowedOrigins() 的空参重载——那在 Spring 里语义不明（可能变成"拒绝全部"）。
            origins = Collections.singletonList("*");
        }

        registry.addHandler(detectWebSocketHandler, "/ws/detect")
                .addInterceptors(detectWebSocketHandshakeInterceptor)
                .setAllowedOrigins(origins.toArray(new String[0]));

        if (webSocketProperties.isWildcard()) {
            log.warn("WebSocket 允许任意来源握手（fact-check.websocket.allowed-origins 含 \"*\"）。"
                    + "本项目鉴权用显式 token 查询参数、非 cookie，因此不构成典型 CSWSH；"
                    + "但生产环境建议改成真实前端域名，见 docs/部署文档.md 安全清单。");
        } else {
            log.info("WebSocket 允许的来源：{}", origins);
        }
    }
}
