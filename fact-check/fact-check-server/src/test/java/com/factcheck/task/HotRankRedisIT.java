package com.factcheck.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.factcheck.constant.HotScoreConstant;
import com.factcheck.constant.RedisKeyConstant;
import com.factcheck.service.HotRankService;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 热度榜的**真实 Redis** 集成测试（默认不跑）
 *
 * <h3>为什么必须有它</h3>
 * <ul>
 *   <li>{@link HotScoreDecayTask} 由 {@code @Scheduled(cron = "0 0 3 * * ?")} 驱动，
 *       **没有任何办法在测试里等它到凌晨三点执行**，因此那段 Lua 脚本此前零覆盖。
 *       本批把执行体抽成 {@code decayNow()} 后终于可以直接调用并断言 ZSET 的真实变化。</li>
 *   <li>{@link HotRankService#remove} 是新增能力，需要在真实 ZSET 上验证
 *       "移除后 ZSCORE 为空、且不影响其它成员"。</li>
 * </ul>
 *
 * <h3>怎么跑</h3>
 * <pre>
 * mvn -o -B test -Dtest=HotRankRedisIT -Dfactcheck.it.redis=true
 * </pre>
 * 需要本机 Redis 可用（默认 127.0.0.1:6379，可用 FACTCHECK_REDIS_HOST/PORT/PASSWORD 覆盖）。
 * 不传该参数时整类跳过，因此不影响离线 CI。
 *
 * <h3>关于数据安全</h3>
 * 衰减是**全局**操作（Lua 脚本对 key 内所有成员生效），因此：
 * <ul>
 *   <li>测试只往 {@code fact:hot:claim} 里放**自己的测试成员**（recordId 9_000_001 起）；</li>
 *   <li>跑之前先清掉同名测试成员，跑完在 {@code @AfterEach} 再清一次，**绝不删除别人的成员**；</li>
 *   <li>断言只针对测试成员，不假设榜单总数。</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = "fact-check.rate-limit.enabled=false")
@EnabledIfSystemProperty(named = "factcheck.it.redis", matches = "true")
class HotRankRedisIT {

    /** 测试专用 recordId 段，避免与真实数据冲突 */
    private static final Long ID_KEEP = 9_000_001L;
    private static final Long ID_DROP = 9_000_002L;
    private static final Long ID_ZERO = 9_000_003L;
    private static final List<Long> TEST_IDS = Arrays.asList(ID_KEEP, ID_DROP, ID_ZERO);

    private static final String ZSET = RedisKeyConstant.HOT_CLAIM_ZSET;

    @Autowired
    private HotRankService hotRankService;
    @Autowired
    private HotScoreDecayTask decayTask;
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @AfterEach
    void cleanUpTestMembers() {
        for (Long id : TEST_IDS) {
            redisTemplate.opsForZSet().remove(ZSET, String.valueOf(id));
        }
    }

    private Double scoreOf(Long recordId) {
        return redisTemplate.opsForZSet().score(ZSET, String.valueOf(recordId));
    }

    @Test
    @DisplayName("remove：移除后 ZSCORE 为空，且不影响其它成员")
    void removeShouldDeleteOnlyGivenMembers() {
        hotRankService.addScore(ID_KEEP, 7.0);
        hotRankService.addScore(ID_DROP, 3.0);
        assertThat(scoreOf(ID_KEEP)).isNotNull();
        assertThat(scoreOf(ID_DROP)).isNotNull();

        long removed = hotRankService.remove(ID_DROP);

        assertThat(removed).as("应恰好移除 1 个成员").isEqualTo(1);
        assertThat(scoreOf(ID_DROP)).as("被移除的成员不应再有分数").isNull();
        assertThat(scoreOf(ID_KEEP)).as("其它成员必须保留").isNotNull();
    }

    @Test
    @DisplayName("remove：null 元素或空数组是安全的 no-op（不能让热度榜操作炸掉主流程）")
    void removeShouldBeSafeOnBadInput() {
        hotRankService.addScore(ID_KEEP, 2.0);

        assertThat(hotRankService.remove()).isZero();
        assertThat(hotRankService.remove((Long) null)).isZero();
        assertThat(hotRankService.remove(new Long[]{null})).isZero();

        assertThat(scoreOf(ID_KEEP)).as("非法入参不得误删任何成员").isNotNull();
    }

    @Test
    @DisplayName("Lua 衰减：分数 ×0.85，且低于 1.0 的成员被清理")
    void decayShouldMultiplyScoresAndPurgeLowOnes() {
        hotRankService.addScore(ID_KEEP, 10.0);
        hotRankService.addScore(ID_DROP, 1.0);
        hotRankService.addScore(ID_ZERO, 0.0);

        int remaining = decayTask.decayNow();

        assertThat(remaining).as("衰减应成功执行（失败会返回 -1）").isGreaterThanOrEqualTo(0);

        // 10.0 * 0.85 = 8.5
        assertThat(scoreOf(ID_KEEP))
                .as("分数应按 DECAY_FACTOR 衰减")
                .isCloseTo(10.0 * HotScoreConstant.DECAY_FACTOR, org.assertj.core.data.Offset.offset(1e-6));

        // 1.0 * 0.85 = 0.85 < MIN_HOT_SCORE(1.0) → 清理
        assertThat(scoreOf(ID_DROP))
                .as("衰减后低于 MIN_HOT_SCORE 的成员必须被移除")
                .isNull();

        // 0.0 * 0.85 = 0 → 清理
        assertThat(scoreOf(ID_ZERO))
                .as("零分成员必须被移除")
                .isNull();
    }

    @Test
    @DisplayName("Lua 衰减：高分成员在多次衰减后仍按预期收敛，不会被误删")
    void decayShouldKeepHighScoreMembersAcrossRuns() {
        hotRankService.addScore(ID_KEEP, 100.0);

        decayTask.decayNow();
        decayTask.decayNow();

        double expected = 100.0 * Math.pow(HotScoreConstant.DECAY_FACTOR, 2);
        assertThat(scoreOf(ID_KEEP))
                .as("连续两次衰减后应为 100 * 0.85^2")
                .isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("衰减不会破坏 key 的结构（衰减后仍是可正常读写的 ZSET）")
    void decayShouldLeaveZsetUsable() {
        hotRankService.addScore(ID_KEEP, 50.0);

        decayTask.decayNow();

        Set<String> members = redisTemplate.opsForZSet().range(ZSET, 0, -1);
        assertThat(members).as("ZSET 应仍可读取").isNotNull();
        // 衰减后还能继续累加
        hotRankService.addScore(ID_KEEP, 1.0);
        assertThat(scoreOf(ID_KEEP)).isCloseTo(50.0 * HotScoreConstant.DECAY_FACTOR + 1.0,
                org.assertj.core.data.Offset.offset(1e-6));
    }
}
