package com.factcheck.constant;

/**
 * Redis key 常量，避免魔法字符串
 */
public class RedisKeyConstant {
    /** 热门消息 ZSET key */
    public static final String HOT_CLAIM_ZSET = "fact:hot:claim";
    /**
     * 限流 key 统一前缀，完整形式 `fact:limit:{场景}:{身份}`
     *
     * <p>注意：检测场景拼出来的 key 与旧版 {@link #DETECT_LIMIT_PREFIX} 完全一致
     * （`fact:limit:detect:{userId}`），升级时不会丢失已有计数。</p>
     */
    public static final String RATE_LIMIT_PREFIX = "fact:limit:";
    /** 检测限流 key 前缀（保留给文档与旧代码引用，实际由 RATE_LIMIT_PREFIX + 场景 拼出） */
    public static final String DETECT_LIMIT_PREFIX = "fact:limit:detect:";
    /** 记录详情缓存 key 前缀 */
    public static final String RECORD_CACHE_PREFIX = "fact:cache:record:";

    private RedisKeyConstant() {
    }
}
