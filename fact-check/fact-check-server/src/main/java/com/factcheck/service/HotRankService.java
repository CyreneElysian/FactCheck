package com.factcheck.service;

import com.factcheck.dto.HotEntry;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 热度榜读写服务（Redis ZSET `fact:hot:claim`）
 *
 * <p>把"热度怎么读、怎么写"收敛到一处：原先 ZSET 的读逻辑只存在于用户端热度榜，
 * 写逻辑则在三个 Service 里各写了一份 try/catch + incrementScore。</p>
 *
 * <p>所有方法都**不抛异常**：热度是辅助功能，Redis 抖动不该影响检测主流程。
 * 读失败返回空列表，由调用方降级到数据库排序。</p>
 */
public interface HotRankService {

    /**
     * 按热度降序取前 limit 条
     *
     * @return 条目列表；Redis 异常或榜单为空时返回空列表
     */
    List<HotEntry> top(int limit);

    /**
     * 取前 limit 条的记录 ID（按热度降序）
     */
    default List<Long> topRecordIds(int limit) {
        return top(limit).stream().map(HotEntry::getRecordId).collect(Collectors.toList());
    }

    /**
     * 累加热度
     *
     * @param recordId 记录 ID
     * @param delta    增量（见 {@link com.factcheck.constant.HotScoreConstant}）
     */
    void addScore(Long recordId, double delta);

    /**
     * 在去重窗口内累加一次热度
     *
     * <p>同一 {@code scope}（例如 `user:12`）对同一记录在 {@code windowMinutes} 分钟内只计一次；
     * Redis 异常时**不计数**——宁可少计，也不要因为失败重试而重复计分（那等于给刷榜留后门）。</p>
     *
     * @return 本次是否真的计了分
     */
    boolean addScoreOnce(Long recordId, double delta, String scope, int windowMinutes);

    /**
     * 从热度榜移除若干记录
     *
     * <p>用于"记录已不该再出现在榜上"的场景。当前没有删除记录的接口，但有两处真实需要：</p>
     * <ul>
     *   <li>用户注销（status=2）后，其记录仍可能占据热度榜——产品文档 §4.5 要求注销后
     *       记录保留但脱敏展示，出现在公开的 Top10 里与"脱敏"的意图相悖；</li>
     *   <li>运维直接清理历史数据时，ZSET 会留下指向不存在记录的成员，
     *       虽然读取时会过滤，但"原始分数"会一直留到每日衰减。</li>
     * </ul>
     *
     * <p>与其它方法一致：**不抛异常**（Redis 抖动不该影响主流程），但会记日志。</p>
     *
     * @return 实际移除的成员数；Redis 异常时返回 0
     */
    long remove(Long... recordIds);
}
