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
    /**
     * ⚠️ <b>占位配置，当前不参与任何判定</b>：语义去重的相似度阈值实际由 Python 侧
     * {@code DEDUP_THRESHOLD} 决定（见 {@code fact-agent/.env.example}）。
     * 改这里的值不会影响去重行为，也不会产生任何告警——保留该字段只是为了与
     * 《技术方案》§5.4 的配置清单一致。
     *
     * <p>注意与下面三项区分：{@code exactEnabled} / {@code semanticEnabled} /
     * {@code minTextLength} 是**真正生效**的，由
     * {@code DetectServiceImpl.submit} 在精确去重与语义去重分支中读取。</p>
     */
    private double semanticThreshold;
    /**
     * 精确去重开关，生效于 {@code DetectServiceImpl.submit}
     */
    private boolean exactEnabled;
    /**
     * 语义去重开关，生效于 {@code DetectServiceImpl.submit}
     */
    private boolean semanticEnabled;
    /**
     * 低于该长度只做精确去重、不做语义去重，生效于 {@code DetectServiceImpl.submit}
     */
    private int minTextLength;
    /**
     * 内部鉴权令牌（P2-21）：作为请求头 X-Internal-Token 发给 Python Agent。
     * 必须与 Python 侧 .env 的 INTERNAL_TOKEN 一致；生产环境通过环境变量注入。
     */
    private String internalToken;
}
