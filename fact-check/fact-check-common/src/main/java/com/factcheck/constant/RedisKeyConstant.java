package com.factcheck.constant;

/**
 * Redis key 常量，避免魔法字符串
 */
public class RedisKeyConstant {
    /** 热门消息 ZSET key */
    public static final String HOT_CLAIM_ZSET = "fact:hot:claim";
    /**
     * 查看详情计热度的去重 key 前缀，完整形式 `fact:hot:view:{身份}:{recordId}`
     *
     * <p>用于"同一用户对同一记录在窗口内只计一次"，避免刷新详情页把热度刷上榜。</p>
     */
    public static final String HOT_VIEW_DEDUP_PREFIX = "fact:hot:view:";
    /**
     * 限流 key 统一前缀，完整形式 `fact:limit:{场景}:{身份}`
     *
     * <p>注意：检测场景拼出来的 key 与旧版 {@link #DETECT_LIMIT_PREFIX} 完全一致
     * （`fact:limit:detect:{userId}`），升级时不会丢失已有计数。</p>
     */
    public static final String RATE_LIMIT_PREFIX = "fact:limit:";
    /** 检测限流 key 前缀（保留给文档与旧代码引用，实际由 RATE_LIMIT_PREFIX + 场景 拼出） */
    public static final String DETECT_LIMIT_PREFIX = "fact:limit:detect:";

    // 说明（P2-28 决策）：原设计里还有一个 `fact:cache:record:{id}`（记录详情缓存 30 分钟），
    // 该常量一直是零引用的死代码，已删除。原因是记录状态会**异步变化**
    // （AI 检测完成、管理员人工诊断、申诉改判），缓存详情很容易让用户看到过期状态，
    // 脏读风险大于收益；详见《技术方案》5.3。

    private RedisKeyConstant() {
    }
}
