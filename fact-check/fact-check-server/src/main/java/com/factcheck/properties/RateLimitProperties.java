package com.factcheck.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 限流配置，对应 application.yml 中的 fact-check.rate-limit
 *
 * <p>阈值集中在这里，便于一处审计与按环境调整（校园网/办公网出口 IP 相同，
 * 按 IP 限流的阈值需要比按账号限流宽松一些）。</p>
 */
@Component
@ConfigurationProperties(prefix = "fact-check.rate-limit")
@Data
public class RateLimitProperties {

    /**
     * 是否启用限流（关闭后所有场景直接放行，仅用于本地压测）
     */
    private boolean enabled = true;

    /**
     * 同一用户每天最多提交多少次检测
     */
    private int detectPerDay = 50;

    /**
     * 同一用户名在窗口内最多尝试登录多少次
     */
    private int loginPerUsername = 5;

    /**
     * 同一 IP 在窗口内最多尝试登录多少次
     */
    private int loginPerIp = 20;

    /**
     * 登录限流窗口（分钟）
     */
    private int loginWindowMinutes = 10;

    /**
     * 同一 IP 在窗口内最多注册多少次
     */
    private int registerPerIp = 5;

    /**
     * 注册限流窗口（分钟）
     */
    private int registerWindowMinutes = 60;
}
