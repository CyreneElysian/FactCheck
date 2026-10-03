package com.factcheck.config;

import com.factcheck.handler.DetectWebSocketHandler;
import com.factcheck.interceptor.DetectWebSocketHandshakeInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置
 * 检测结果通过 /api/ws/detect 实时推送给前端
 */
@Configuration
@EnableWebSocket
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

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(detectWebSocketHandler, "/ws/detect")
                .addInterceptors(detectWebSocketHandshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
