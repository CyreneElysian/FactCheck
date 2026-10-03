package com.factcheck.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.factcheck.dto.HotEntry;
import com.factcheck.entity.FactCheckRecord;
import com.factcheck.exception.RecordNotFoundException;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.service.DetectService;
import com.factcheck.service.HotRankService;
import com.factcheck.vo.DetectRecordVO;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 热度榜公开详情测试（P3-34）
 *
 * <p>锁定的是一条**越权边界**：热度榜是公开的，但检测详情是"仅归属者可见"。
 * 公开详情接口必须且只能公开"当前确实在 Top10 内"的记录，
 * 否则遍历 id 就能读到任何人的消息原文、推理链与证据（IDOR）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HotServiceImplPublicDetailTest {

    @Mock
    private HotRankService hotRankService;
    @Mock
    private FactCheckRecordMapper factCheckRecordMapper;
    @Mock
    private DetectService detectService;

    @InjectMocks
    private HotServiceImpl hotService;

    private void rankedRecord(Long recordId) {
        when(hotRankService.top(10)).thenReturn(Collections.singletonList(
                HotEntry.builder().recordId(recordId).score(12.5).build()));
        when(factCheckRecordMapper.getById(recordId)).thenReturn(FactCheckRecord.builder()
                .id(recordId).content("武汉又出现新型病毒了").category("健康")
                .status(2).verdict(2).hitCount(8).build());
    }

    @Test
    @DisplayName("记录在热度榜 Top10 内 → 返回公开视图")
    void shouldReturnDetailForRankedRecord() {
        rankedRecord(7L);
        when(detectService.buildPublicRecord(7L)).thenReturn(DetectRecordVO.builder()
                .id(7L).content("武汉又出现新型病毒了").verdict(2).build());

        DetectRecordVO vo = hotService.publicDetail(7L);

        assertThat(vo.getId()).isEqualTo(7L);
        verify(detectService).buildPublicRecord(7L);
    }

    @Test
    @DisplayName("记录不在热度榜内 → 拒绝（否则遍历 id 即可读别人内容）")
    void shouldRejectRecordOutsideTopList() {
        rankedRecord(7L);

        assertThatThrownBy(() -> hotService.publicDetail(999L))
                .isInstanceOf(RecordNotFoundException.class);

        verify(detectService, never()).buildPublicRecord(anyLong());
    }

    @Test
    @DisplayName("热度榜为空（Redis 不可用等）→ 一律拒绝，不退回“直接查库”")
    void shouldRejectWhenRankIsEmpty() {
        when(hotRankService.top(10)).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> hotService.publicDetail(7L))
                .isInstanceOf(RecordNotFoundException.class);

        verify(detectService, never()).buildPublicRecord(anyLong());
    }

    @Test
    @DisplayName("recordId 为 null → 拒绝")
    void shouldRejectNullId() {
        assertThatThrownBy(() -> hotService.publicDetail(null))
                .isInstanceOf(RecordNotFoundException.class);
    }

    @Test
    @DisplayName("榜内记录若已不是“已完成且有结论”，不会出现在 top10 里 → 同样被拒绝")
    void shouldRejectUnfinishedRecord() {
        when(hotRankService.top(10)).thenReturn(Collections.singletonList(
                HotEntry.builder().recordId(7L).score(12.5).build()));
        // top10() 会过滤掉未完成的记录，因此它不在返回列表里
        when(factCheckRecordMapper.getById(7L)).thenReturn(FactCheckRecord.builder()
                .id(7L).status(0).build());

        assertThatThrownBy(() -> hotService.publicDetail(7L))
                .isInstanceOf(RecordNotFoundException.class);
        verify(detectService, never()).buildPublicRecord(anyLong());
    }

    @Test
    @DisplayName("榜内多条记录时只放行命中的那一条")
    void shouldOnlyAllowListedRecord() {
        when(hotRankService.top(10)).thenReturn(List.of(
                HotEntry.builder().recordId(1L).score(20.0).build(),
                HotEntry.builder().recordId(2L).score(10.0).build()));
        when(factCheckRecordMapper.getById(1L)).thenReturn(FactCheckRecord.builder()
                .id(1L).content("a").status(2).verdict(1).build());
        when(factCheckRecordMapper.getById(2L)).thenReturn(FactCheckRecord.builder()
                .id(2L).content("b").status(2).verdict(2).build());
        when(detectService.buildPublicRecord(2L)).thenReturn(DetectRecordVO.builder().id(2L).build());

        assertThat(hotService.publicDetail(2L).getId()).isEqualTo(2L);
        assertThatThrownBy(() -> hotService.publicDetail(3L))
                .isInstanceOf(RecordNotFoundException.class);
    }
}
