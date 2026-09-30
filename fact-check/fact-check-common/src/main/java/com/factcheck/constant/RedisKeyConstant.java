package com.factcheck.constant;

/**
 * Redis key 常量，避免魔法字符串
 */
public class RedisKeyConstant {
    /** 热门消息 ZSET key */
    public static final String HOT_CLAIM_ZSET = "fact:hot:claim";
    /** 检测限流 key 前缀 */
    public static final String DETECT_LIMIT_PREFIX = "fact:limit:detect:";
    /** 记录详情缓存 key 前缀 */
    public static final String RECORD_CACHE_PREFIX = "fact:cache:record:";
}
