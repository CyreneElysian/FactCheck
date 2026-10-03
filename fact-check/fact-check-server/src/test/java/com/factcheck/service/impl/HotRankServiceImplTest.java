package com.factcheck.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.factcheck.constant.RedisKeyConstant;
import com.factcheck.dto.HotEntry;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

/**
 * {@link HotRankServiceImpl} 测试（P2-14）
 *
 * <p>热度是辅助功能：**读写失败都不能影响主流程**，读失败要能让调用方降级到数据库，
 * 写失败只记日志；而"查看详情"这类可被用户反复触发的行为必须有去重窗口，否则可以刷榜。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HotRankServiceImplTest {

    @Mock
    private RedisTemplate redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private HotRankServiceImpl hotRankService;

    private static ZSetOperations.TypedTuple<String> tuple(String member, double score) {
        return new ZSetOperations.TypedTuple<String>() {
            @Override
            public String getValue() {
                return member;
            }

            @Override
            public Double getScore() {
                return score;
            }

            @Override
            public int compareTo(ZSetOperations.TypedTuple<String> o) {
                return Double.compare(score, o.getScore() == null ? 0 : o.getScore());
            }
        };
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    @Test
    @DisplayName("top：按热度降序返回记录 ID 与分值")
    void shouldReturnHotEntriesInOrder() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.reverseRangeWithScores(RedisKeyConstant.HOT_CLAIM_ZSET, 0, 1))
                .thenReturn(new LinkedHashSet<>(Arrays.asList(tuple("12", 6.5), tuple("7", 3.0))));

        List<HotEntry> entries = hotRankService.top(2);

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getRecordId()).isEqualTo(12L);
        assertThat(entries.get(0).getScore()).isEqualTo(6.5);
        assertThat(entries.get(1).getRecordId()).isEqualTo(7L);
        assertThat(hotRankService.topRecordIds(2)).containsExactly(12L, 7L);
    }

    @Test
    @DisplayName("top：榜单里的非法成员被跳过，不影响其余条目")
    void shouldSkipInvalidMembers() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.reverseRangeWithScores(anyString(), anyLong(), anyLong()))
                .thenReturn(new LinkedHashSet<>(Arrays.asList(tuple("abc", 9.0), tuple("5", 4.0))));

        List<HotEntry> entries = hotRankService.top(10);

        assertThat(entries).extracting(HotEntry::getRecordId).containsExactly(5L);
    }

    @Test
    @DisplayName("top：Redis 异常时返回空列表（调用方据此降级），不抛异常")
    void shouldReturnEmptyWhenRedisFails() {
        when(redisTemplate.opsForZSet()).thenThrow(new RuntimeException("Redis 挂了"));

        assertThat(hotRankService.top(10)).isEmpty();
    }

    @Test
    @DisplayName("top：limit <= 0 时不访问 Redis")
    void shouldNotQueryWhenLimitInvalid() {
        assertThat(hotRankService.top(0)).isEmpty();

        verify(redisTemplate, never()).opsForZSet();
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    @Test
    @DisplayName("addScore：写入 ZSET（成员为 recordId 字符串）")
    void shouldIncrementScore() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        hotRankService.addScore(12L, 5.0);

        verify(zSetOperations).incrementScore(RedisKeyConstant.HOT_CLAIM_ZSET, "12", 5.0);
    }

    @Test
    @DisplayName("addScore：Redis 异常只记日志，不抛异常（不影响检测主流程）")
    void shouldNotThrowWhenIncrementFails() {
        when(redisTemplate.opsForZSet()).thenThrow(new RuntimeException("Redis 挂了"));

        hotRankService.addScore(12L, 5.0);
    }

    @Test
    @DisplayName("addScoreOnce：首次调用计分，窗口内重复调用不再计分")
    void shouldOnlyScoreOnceWithinWindow() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), eq(TimeUnit.MINUTES)))
                .thenReturn(true, false);

        assertThat(hotRankService.addScoreOnce(12L, 0.5, "user:1", 30)).isTrue();
        assertThat(hotRankService.addScoreOnce(12L, 0.5, "user:1", 30)).isFalse();

        // 只有第一次计分（去重检查每次都做，但它拦住了第二次加分）
        verify(zSetOperations).incrementScore(RedisKeyConstant.HOT_CLAIM_ZSET, "12", 0.5);
        // 去重 key 带身份与记录 ID，且窗口来自配置
        verify(valueOperations, times(2)).setIfAbsent(
                RedisKeyConstant.HOT_VIEW_DEDUP_PREFIX + "user:1:12", "1", 30L, TimeUnit.MINUTES);
    }

    @Test
    @DisplayName("addScoreOnce：不同用户对同一记录各自计分（互不影响）")
    void shouldScorePerUser() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        Set<String> keys = new LinkedHashSet<>();
        hotRankService.addScoreOnce(12L, 0.5, "user:1", 30);
        hotRankService.addScoreOnce(12L, 0.5, "user:2", 30);

        verify(valueOperations).setIfAbsent(
                RedisKeyConstant.HOT_VIEW_DEDUP_PREFIX + "user:1:12", "1", 30L, TimeUnit.MINUTES);
        verify(valueOperations).setIfAbsent(
                RedisKeyConstant.HOT_VIEW_DEDUP_PREFIX + "user:2:12", "1", 30L, TimeUnit.MINUTES);
    }

    @Test
    @DisplayName("addScoreOnce：Redis 异常时**不计分**（宁可少计也不要重复计分给刷榜留后门）")
    void shouldNotScoreWhenDedupCheckFails() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new RuntimeException("Redis 挂了"));

        assertThat(hotRankService.addScoreOnce(12L, 0.5, "user:1", 30)).isFalse();

        verify(redisTemplate, never()).opsForZSet();
    }

    @Test
    @DisplayName("addScoreOnce：缺少身份时不计分（避免退化成无去重的计分）")
    void shouldNotScoreWithoutScope() {
        assertThat(hotRankService.addScoreOnce(12L, 0.5, null, 30)).isFalse();
        assertThat(hotRankService.addScoreOnce(12L, 0.5, "", 30)).isFalse();

        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("topRecordIds：空榜单返回空列表")
    void shouldHandleEmptyRank() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.reverseRangeWithScores(anyString(), anyLong(), anyLong()))
                .thenReturn(Collections.emptySet());

        assertThat(hotRankService.topRecordIds(10)).isEmpty();
    }

    @Test
    @DisplayName("addScore：recordId 为空或增量为 0 时不做任何操作")
    void shouldSkipInvalidScore() {
        hotRankService.addScore(null, 5.0);
        hotRankService.addScore(12L, 0.0);

        verify(redisTemplate, never()).opsForZSet();
    }
}
