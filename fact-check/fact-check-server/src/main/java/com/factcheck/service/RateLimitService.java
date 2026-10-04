package com.factcheck.service;

/**
 * 限流服务（S9）
 *
 * <p>统一实现"Redis 计数 + 超阈值拒绝"，覆盖三个场景：检测提交、登录、注册。
 * 阈值与窗口来自 {@link com.factcheck.properties.RateLimitProperties}，便于一处审计与按环境调整。</p>
 *
 * <h3>两个刻意的设计取舍</h3>
 * <ul>
 *   <li><b>只做频次限流，不锁定账号</b>：技术方案早期版本写的是"登录失败 5 次锁定账号 10 分钟"，
 *       但那会被用来做拒绝服务——攻击者只要知道用户名，故意输错 5 次就能把真实用户锁在门外。
 *       这里改为"按用户名 + 按 IP 双维度限流"：既能拦住针对单账号的爆破与批量撞库，
 *       又不会让别人替你把账号锁死。</li>
 *   <li><b>Redis 异常时放行（fail-open）</b>：限流是防护措施而非核心功能，
 *       若 Redis 抖动就拒绝所有请求，等于把可用性押在缓存上。
 *       失败时会打 WARN 日志，便于发现"限流实际上没生效"。</li>
 * </ul>
 */
public interface RateLimitService {

    /**
     * 通用计数限流：命中则计数 +1，超过 {@code limit} 抛
     * {@link com.factcheck.exception.RateLimitException}
     *
     * @param scene         场景名（会拼进 Redis key）
     * @param identity      身份标识（用户 ID / 用户名 / IP）；为空时直接放行
     * @param limit         窗口内允许的最大次数
     * @param windowSeconds 窗口长度（秒）
     */
    void check(String scene, String identity, int limit, int windowSeconds);

    /**
     * 检测提交限流：同一用户每天最多 {@code fact-check.rate-limit.detect-per-day} 次
     */
    void checkDetectSubmit(Long userId);

    /**
     * 登录限流：按用户名（防针对单账号爆破）与按 IP（防批量撞库）双维度
     *
     * @param username 原始用户名，可为空（为空时只按 IP 限流）
     * @param ip       客户端 IP，可为空（取不到时只按用户名限流）
     */
    void checkLogin(String username, String ip);

    /**
     * 注册限流：按 IP，防止批量注册
     */
    void checkRegister(String ip);

    /**
     * 微信绑定限流：按 IP
     *
     * <p>该接口在鉴权白名单里（未登录可访问），且每次调用都会请求微信 code2session，
     * 因此必须按 IP 限流，避免被当作刷第三方接口的跳板。</p>
     */
    void checkWechatBind(String ip);
}
