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

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(detectWebSocketHandler, "/ws/detect")
                .addInterceptors(new DetectWebSocketHandshakeInterceptor())
                .setAllowedOrigins("*");
    }
}
