package com.factcheck.interceptor;

import com.factcheck.constant.JwtClaimsConstant;
import com.factcheck.entity.FactCheckRecord;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.properties.JwtProperties;
import com.factcheck.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * WebSocket 握手拦截器：解析 recordId 并**校验订阅权限**（P5-58）
 *
 * <p>早期实现只解析 recordId，任何人都能连上 {@code /ws/detect?recordId=N}
 * 并实时收到**别人**检测结果（结论、置信度、推理链）。现在要求：</p>
 * <ol>
 *   <li>必须带 {@code recordId}；</li>
 *   <li>必须带有效的用户端 JWT（浏览器无法给 WebSocket 设自定义头，因此走查询参数 {@code token}）；</li>
 *   <li>该记录必须属于 token 对应的用户。</li>
 * </ol>
 *
 * <p>任一条不满足即拒绝握手（返回 false，连接不会建立）。</p>
 */
@Component
@Slf4j
public class DetectWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    /**
     * 握手时用于传递 token 的查询参数名（浏览器无法自定义请求头）
     */
    static final String TOKEN_PARAM = "token";

    /**
     * 握手通过后写入会话属性的键
     */
    public static final String ATTR_RECORD_ID = "recordId";
    public static final String ATTR_USER_ID = "userId";

    @Autowired
    private JwtProperties jwtProperties;
    @Autowired
    private FactCheckRecordMapper factCheckRecordMapper;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        var queryParams = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();

        String recordIdText = queryParams.getFirst("recordId");
        if (recordIdText == null || recordIdText.isEmpty()) {
            log.warn("WebSocket 握手被拒：缺少 recordId");
            return false;
        }
        Long recordId;
        try {
            recordId = Long.valueOf(recordIdText);
        } catch (NumberFormatException e) {
            log.warn("WebSocket 握手被拒：recordId 非法={}", recordIdText);
            return false;
        }

        String token = queryParams.getFirst(TOKEN_PARAM);
        if (token == null || token.isEmpty()) {
            log.warn("WebSocket 握手被拒：缺少 token（recordId={}）", recordId);
            return false;
        }
        Long userId;
        try {
            Claims claims = JwtUtil.parseJWT(jwtProperties.getUserSecretKey(), token);
            userId = Long.valueOf(claims.get(JwtClaimsConstant.USER_ID).toString());
        } catch (Exception e) {
            log.warn("WebSocket 握手被拒：token 无效（recordId={}, error={}）", recordId, e.getMessage());
            return false;
        }

        FactCheckRecord record = factCheckRecordMapper.getById(recordId);
        if (record == null) {
            log.warn("WebSocket 握手被拒：记录不存在（recordId={}）", recordId);
            return false;
        }
        if (!userId.equals(record.getUserId())) {
            log.warn("WebSocket 握手被拒：越权订阅（recordId={}, 记录归属={}, 请求者={}）",
                    recordId, record.getUserId(), userId);
            return false;
        }

        attributes.put(ATTR_RECORD_ID, recordId);
        attributes.put(ATTR_USER_ID, userId);
        log.info("WebSocket 握手通过：recordId={}, userId={}", recordId, userId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
