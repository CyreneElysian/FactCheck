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

    /**
     * "检测中"卡死多久之后允许被重新认领（分钟），默认 10。
     *
     * <h3>为什么需要它</h3>
     * 检测是异步跑的（{@code @Async}），任务只存在于内存里。后端崩溃/重启时，
     * 正在检测的记录会**永远留在 status=0（检测中）**：
     * <ul>
     *   <li>用户看到"检测中"一直不动；</li>
     *   <li>重新提交同一句 → 精确去重命中同一行 → 但 {@code handleExactHit} 只对
     *       {@code status==3}（失败）重试，status=0 会直接返回 → **卡死无法自救**。</li>
     * </ul>
     * 加上这个阈值后，卡住够久的记录可以在下次提交同一内容时被**原子认领**并重跑。
     *
     * <p>不能取太小：正常检测耗时可达数十秒，阈值过短会让"用户连续点两次提交"
     * 变成两次真实 Agent 调用。10 分钟远大于任何正常检测耗时，足够安全。</p>
     */
    private int detectingStaleMinutes = 10;
}
