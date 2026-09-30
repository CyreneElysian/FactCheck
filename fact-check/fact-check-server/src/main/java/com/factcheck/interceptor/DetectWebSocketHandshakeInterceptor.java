package com.factcheck.interceptor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * WebSocket 握手拦截器：从查询参数中解析 recordId
 */
@Component
@Slf4j
public class DetectWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String recordId = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("recordId");
        if (recordId != null) {
            try {
                attributes.put("recordId", Long.valueOf(recordId));
                log.info("WebSocket 握手：recordId={}", recordId);
            } catch (NumberFormatException e) {
                log.warn("WebSocket 握手 recordId 非法：{}", recordId);
                return false;
            }
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
