package com.factcheck.service.impl;

import com.factcheck.constant.MessageConstant;
import com.factcheck.constant.RedisKeyConstant;
import com.factcheck.exception.RateLimitException;
import com.factcheck.properties.RateLimitProperties;
import com.factcheck.service.RateLimitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 限流服务实现：Redis INCR + EXPIRE
 *
 * <p>key 形式为 {@code fact:limit:{场景}:{身份}}，例如
 * {@code fact:limit:detect:12}、{@code fact:limit:login:user:alice}、{@code fact:limit:login:ip:203.0.113.7}。
 * 检测场景的 key 与旧实现完全一致，升级不会丢失已有计数。</p>
 */
@Service
@Slf4j
public class RateLimitServiceImpl implements RateLimitService {

    /**
     * 场景名，同时作为 Redis key 中的一段
     */
    static final String SCENE_DETECT = "detect";
    static final String SCENE_LOGIN_USER = "login:user";
    static final String SCENE_LOGIN_IP = "login:ip";
    static final String SCENE_REGISTER_IP = "register:ip";

    /**
     * 身份标识的最大长度：避免超长用户名/伪造 IP 造出巨型 Redis key
     */
    private static final int MAX_IDENTITY_LENGTH = 64;

    private static final int SECONDS_PER_DAY = (int) TimeUnit.DAYS.toSeconds(1);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RateLimitProperties properties;

    /**
     * 启动即打印生效阈值：便于确认配置真的绑上了（也方便面试时一眼看到限流策略）
     */
    @PostConstruct
    void logPolicy() {
        if (!properties.isEnabled()) {
            log.warn("限流已关闭（fact-check.rate-limit.enabled=false），所有场景直接放行");
            return;
        }
        log.info("限流已启用：检测 {} 次/天/用户；登录 {} 次/{} 分钟（按用户名）、{} 次/{} 分钟（按 IP，"
                        + "IP 取自 X-Forwarded-For）；注册 {} 次/{} 分钟（按 IP）；只限流不锁定账号",
                properties.getDetectPerDay(),
                properties.getLoginPerUsername(), properties.getLoginWindowMinutes(),
                properties.getLoginPerIp(), properties.getLoginWindowMinutes(),
                properties.getRegisterPerIp(), properties.getRegisterWindowMinutes());
    }

    @Override
    public void check(String scene, String identity, int limit, int windowSeconds) {
        if (!properties.isEnabled()) {
            return;
        }
        String key = buildKey(scene, identity);
        if (key == null || limit <= 0 || windowSeconds <= 0) {
            return;
        }
        try {
            Long count = stringRedisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                // 只在第一次计数时设置过期，避免每次请求都刷新窗口（那会变成"滑动窗口"，
                // 高频请求下计数永远不过期）
                stringRedisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
            }
            if (count != null && count > limit) {
                log.warn("触发限流：scene={}, identity={}, count={}, limit={}, window={}s",
                        scene, identity, count, limit, windowSeconds);
                throw new RateLimitException(MessageConstant.RATE_LIMIT);
            }
        } catch (RateLimitException e) {
            throw e;
        } catch (Exception e) {
            // fail-open：限流不该成为可用性的单点（见接口注释中的取舍说明）
            log.warn("限流检查失败（Redis 异常），本次放行：scene={}, identity={}, error={}",
                    scene, identity, e.getMessage());
        }
    }

    @Override
    public void checkDetectSubmit(Long userId) {
        check(SCENE_DETECT, userId == null ? null : String.valueOf(userId),
                properties.getDetectPerDay(), SECONDS_PER_DAY);
    }

    @Override
    public void checkLogin(String username, String ip) {
        int window = windowSeconds(properties.getLoginWindowMinutes());
        check(SCENE_LOGIN_USER, normalizeUsername(username), properties.getLoginPerUsername(), window);
        check(SCENE_LOGIN_IP, ip, properties.getLoginPerIp(), window);
    }

    @Override
    public void checkRegister(String ip) {
        check(SCENE_REGISTER_IP, ip, properties.getRegisterPerIp(),
                windowSeconds(properties.getRegisterWindowMinutes()));
    }

    /**
     * 用户名归一化：去空白 + 转小写
     *
     * <p>转小写是必要的：MySQL 默认排序规则下用户名大小写不敏感
     * （唯一索引 {@code uk_username} 会把 Alice 与 alice 视为同一个账号），
     * 若不归一，攻击者只要变换大小写就能绕过按用户名的限流。</p>
     */
    private static String normalizeUsername(String username) {
        if (username == null) {
            return null;
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private static int windowSeconds(int minutes) {
        return (int) TimeUnit.MINUTES.toSeconds(Math.max(1, minutes));
    }

    /**
     * @return Redis key；场景或身份为空（含纯空白）时返回 null（调用方据此跳过）
     */
    private static String buildKey(String scene, String identity) {
        if (scene == null || scene.trim().isEmpty() || identity == null) {
            return null;
        }
        // trim 后再判空：纯空白的身份（如 "  "）若放过去会生成 "fact:limit:x:  " 这类无意义 key
        String value = identity.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_IDENTITY_LENGTH) {
            value = value.substring(0, MAX_IDENTITY_LENGTH);
        }
        return RedisKeyConstant.RATE_LIMIT_PREFIX + scene + ":" + value;
    }
}
