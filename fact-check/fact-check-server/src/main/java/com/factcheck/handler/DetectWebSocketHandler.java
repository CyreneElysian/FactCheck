package com.factcheck.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 检测结果 WebSocket 处理器
 * 按 recordId 维护连接，检测完成后主动推送结果给前端
 */
@Component
@Slf4j
public class DetectWebSocketHandler extends TextWebSocketHandler {

    private final Map<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long recordId = (Long) session.getAttributes().get("recordId");
        if (recordId != null) {
            sessions.put(recordId, session);
            log.info("WebSocket 连接建立：recordId={}, sessionId={}", recordId, session.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long recordId = (Long) session.getAttributes().get("recordId");
        if (recordId != null) {
            sessions.remove(recordId, session);
            log.info("WebSocket 连接关闭：recordId={}, sessionId={}", recordId, session.getId());
        }
    }

    /**
     * 向指定 recordId 推送消息；未连接则忽略
     */
    public void pushResult(Long recordId, String message) {
        WebSocketSession session = sessions.get(recordId);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(message));
            }
            log.info("WebSocket 推送成功：recordId={}", recordId);
        } catch (IOException e) {
            log.warn("WebSocket 推送失败：recordId={}, error={}", recordId, e.getMessage());
        }
    }
}
