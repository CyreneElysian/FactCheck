package com.factcheck.task;

import com.factcheck.constant.HotScoreConstant;
import com.factcheck.constant.RedisKeyConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * 热度衰减定时任务
 * 每天 03:00 对热门消息 ZSET 的所有 score 乘以衰减系数，并清理低分成员
 */
@Component
@Slf4j
public class HotScoreDecayTask {

    /**
     * Lua 脚本：遍历 ZSET 所有成员，score * 衰减系数 后写回，最后移除低分成员
     */
    private static final String DECAY_LUA_SCRIPT =
            "local members = redis.call('ZRANGE', KEYS[1], 0, -1, 'WITHSCORES') " +
            "for i = 1, #members, 2 do " +
            "  local score = tonumber(members[i + 1]) * tonumber(ARGV[1]) " +
            "  redis.call('ZADD', KEYS[1], score, members[i]) " +
            "end " +
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[2]) " +
            "return redis.call('ZCARD', KEYS[1])";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 每天 03:00 执行热度衰减
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void decay() {
        log.info("开始热度衰减：key={}, decayFactor={}, minScore={}",
                RedisKeyConstant.HOT_CLAIM_ZSET, HotScoreConstant.DECAY_FACTOR, HotScoreConstant.MIN_HOT_SCORE);
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(DECAY_LUA_SCRIPT, Long.class);
            Long memberCount = stringRedisTemplate.execute(
                    script,
                    Collections.singletonList(RedisKeyConstant.HOT_CLAIM_ZSET),
                    String.valueOf(HotScoreConstant.DECAY_FACTOR),
                    String.valueOf(HotScoreConstant.MIN_HOT_SCORE));
            log.info("热度衰减完成：处理 {} 条成员", memberCount == null ? 0 : memberCount);
        } catch (Exception e) {
            log.error("热度衰减失败", e);
        }
    }
}
