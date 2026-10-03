package com.factcheck.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Python Agent 与去重相关配置属性，对应 application.yml 中 fact-check.agent
 */
@Component
@ConfigurationProperties(prefix = "fact-check.agent")
@Data
public class AgentProperties {
    private String baseUrl;
    private int timeout;
    private double semanticThreshold;
    private boolean exactEnabled;
    private boolean semanticEnabled;
    private int minTextLength;
    /**
     * 内部鉴权令牌（P2-21）：作为请求头 X-Internal-Token 发给 Python Agent。
     * 必须与 Python 侧 .env 的 INTERNAL_TOKEN 一致；生产环境通过环境变量注入。
     */
    private String internalToken;
}
