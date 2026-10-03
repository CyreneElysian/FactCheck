package com.factcheck.service.impl;

import com.factcheck.constant.RedisKeyConstant;
import com.factcheck.dto.HotEntry;
import com.factcheck.service.HotRankService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 热度榜读写实现（Redis ZSET）
 *
 * <p>ZSET 成员是 recordId 的字符串形式（{@code RedisConfiguration} 为 RedisTemplate
 * 配置了 String 序列化器，因此成员与分值都能被直接读写，也与演示数据脚本写入的数据兼容）。</p>
 */
@Service
@Slf4j
public class HotRankServiceImpl implements HotRankService {

    @Autowired
    private RedisTemplate redisTemplate;

    @Override
    public List<HotEntry> top(int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        try {
            Set<ZSetOperations.TypedTuple> tuples = redisTemplate.opsForZSet()
                    .reverseRangeWithScores(RedisKeyConstant.HOT_CLAIM_ZSET, 0, limit - 1);
            if (tuples == null || tuples.isEmpty()) {
                return Collections.emptyList();
            }
            List<HotEntry> entries = new ArrayList<>(tuples.size());
            for (ZSetOperations.TypedTuple tuple : tuples) {
                Object value = tuple.getValue();
                if (value == null) {
                    continue;
                }
                try {
                    entries.add(HotEntry.builder()
                            .recordId(Long.valueOf(value.toString()))
                            .score(tuple.getScore())
                            .build());
                } catch (NumberFormatException e) {
                    // 榜单里混入非法成员（例如人工误写）时跳过而不是整段失败
                    log.warn("热度榜存在非法成员，已跳过：{}", value);
                }
            }
            return entries;
        } catch (Exception e) {
            log.error("读取热度榜失败（Redis 异常），调用方应降级到数据库排序", e);
            return Collections.emptyList();
        }
    }

    @Override
    public void addScore(Long recordId, double delta) {
        if (recordId == null || delta == 0) {
            return;
        }
        try {
            redisTemplate.opsForZSet().incrementScore(
                    RedisKeyConstant.HOT_CLAIM_ZSET, String.valueOf(recordId), delta);
            log.info("热度更新：recordId={}, delta={}", recordId, delta);
        } catch (Exception e) {
            log.warn("热度更新失败（不影响主流程）：recordId={}, delta={}, error={}",
                    recordId, delta, e.getMessage());
        }
    }

    @Override
    public boolean addScoreOnce(Long recordId, double delta, String scope, int windowMinutes) {
        if (recordId == null || scope == null || scope.isEmpty()) {
            return false;
        }
        String key = RedisKeyConstant.HOT_VIEW_DEDUP_PREFIX + scope + ":" + recordId;
        try {
            Boolean first = redisTemplate.opsForValue()
                    .setIfAbsent(key, "1", Math.max(1, windowMinutes), TimeUnit.MINUTES);
            if (!Boolean.TRUE.equals(first)) {
                log.debug("处于热度去重窗口内，本次不计分：key={}", key);
                return false;
            }
        } catch (Exception e) {
            // fail-closed：Redis 不可用时宁可不加分，也不要因重试而重复计分（那等于给刷榜留后门）
            log.warn("热度去重检查失败，本次不计分：key={}, error={}", key, e.getMessage());
            return false;
        }
        addScore(recordId, delta);
        return true;
    }
}
