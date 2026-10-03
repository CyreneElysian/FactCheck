package com.factcheck.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.factcheck.entity.FactCheckRecord;
import com.factcheck.mapper.EvidenceMapper;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.service.HotRankService;
import com.factcheck.vo.PendingClaimVO;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 管理端"待诊断 Top10"的排序来源测试（P2-15）
 *
 * <p>锁定：**以 Redis 热度为第一排序依据**（与技术方案 5.1 及用户端热度榜一致），
 * 同时保证"Redis 为空/不可用"时不会返回空列表，而是降级用数据库排序补齐——
 * 缓存不能成为这个功能可用性的前提。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminPendingServiceImplTop10Test {

    private static final int PENDING = 1;
    private static final int DONE = 2;

    @Mock
    private FactCheckRecordMapper factCheckRecordMapper;

    @Mock
    private EvidenceMapper evidenceMapper;

    @Mock
    private HotRankService hotRankService;

    @InjectMocks
    private AdminPendingServiceImpl adminPendingService;

    @BeforeEach
    void stubEvidenceCount() {
        when(evidenceMapper.countByRecordId(anyLong())).thenReturn(3);
    }

    private FactCheckRecord record(long id, int status) {
        return FactCheckRecord.builder()
                .id(id)
                .content("内容" + id)
                .category("健康")
                .status(status)
                .hitCount(1)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
    }

    private List<Long> idsOf(List<PendingClaimVO> list) {
        List<Long> ids = new ArrayList<>();
        list.forEach(vo -> ids.add(vo.getRecordId()));
        return ids;
    }

    @Test
    @DisplayName("优先按 Redis 热度排序：热度榜中的待诊断记录排在最前，且保持热度顺序")
    void shouldRankByRedisHotFirst() {
        // 热度顺序：9（待诊断）→ 5（已完成，应被过滤）→ 3（待诊断）
        when(hotRankService.topRecordIds(anyInt())).thenReturn(Arrays.asList(9L, 5L, 3L));
        when(factCheckRecordMapper.listByIds(anyList()))
                .thenReturn(Arrays.asList(record(3, PENDING), record(5, DONE), record(9, PENDING)));
        // 数据库里的其余待诊断记录，用于补齐
        when(factCheckRecordMapper.listByStatus(PENDING)).thenReturn(Arrays.asList(
                record(3, PENDING), record(9, PENDING), record(1, PENDING), record(2, PENDING)));

        List<PendingClaimVO> result = adminPendingService.top10();

        assertThat(idsOf(result)).startsWith(9L, 3L);
        assertThat(idsOf(result)).doesNotContain(5L);
        assertThat(result).hasSize(4);
    }

    @Test
    @DisplayName("Redis 热度榜为空 → 降级为数据库排序，不返回空列表")
    void shouldFallbackToDatabaseWhenRankEmpty() {
        when(hotRankService.topRecordIds(anyInt())).thenReturn(Collections.emptyList());
        when(factCheckRecordMapper.listByStatus(PENDING)).thenReturn(Arrays.asList(
                record(1, PENDING), record(2, PENDING), record(3, PENDING)));

        List<PendingClaimVO> result = adminPendingService.top10();

        assertThat(idsOf(result)).containsExactly(1L, 2L, 3L);
        verify(factCheckRecordMapper, never()).listByIds(anyList());
    }

    @Test
    @DisplayName("热度榜候选全部已完成 → 仍用数据库补齐，不会因为候选无用而返回空")
    void shouldPadWhenHotCandidatesAreNotPending() {
        when(hotRankService.topRecordIds(anyInt())).thenReturn(Arrays.asList(100L, 101L));
        when(factCheckRecordMapper.listByIds(anyList()))
                .thenReturn(Arrays.asList(record(100, DONE), record(101, DONE)));
        when(factCheckRecordMapper.listByStatus(PENDING)).thenReturn(Arrays.asList(
                record(1, PENDING), record(2, PENDING)));

        List<PendingClaimVO> result = adminPendingService.top10();

        assertThat(idsOf(result)).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("最多返回 10 条，且不出现重复记录")
    void shouldLimitToTenWithoutDuplicates() {
        when(hotRankService.topRecordIds(anyInt())).thenReturn(Arrays.asList(1L, 2L, 3L));
        when(factCheckRecordMapper.listByIds(anyList()))
                .thenReturn(Arrays.asList(record(1, PENDING), record(2, PENDING), record(3, PENDING)));
        // 数据库补齐里包含已入选的 1/2/3，必须去重
        List<FactCheckRecord> dbList = new ArrayList<>();
        for (long id = 1; id <= 15; id++) {
            dbList.add(record(id, PENDING));
        }
        when(factCheckRecordMapper.listByStatus(PENDING)).thenReturn(dbList);

        List<PendingClaimVO> result = adminPendingService.top10();

        assertThat(result).hasSize(10);
        assertThat(idsOf(result)).doesNotHaveDuplicates();
        assertThat(idsOf(result)).startsWith(1L, 2L, 3L);
    }
}
