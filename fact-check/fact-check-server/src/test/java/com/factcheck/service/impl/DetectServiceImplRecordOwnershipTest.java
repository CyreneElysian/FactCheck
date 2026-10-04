package com.factcheck.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.factcheck.context.BaseContext;
import com.factcheck.entity.FactCheckRecord;
import com.factcheck.entity.RecordRating;
import com.factcheck.exception.RecordNotFoundException;
import com.factcheck.exception.UserNotLoginException;
import com.factcheck.mapper.AuthorityInfoMapper;
import com.factcheck.mapper.EvidenceMapper;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.mapper.RecordRatingMapper;
import com.factcheck.mapper.UserFavoriteMapper;
import com.factcheck.properties.HotProperties;
import com.factcheck.service.HotRankService;
import com.factcheck.vo.DetectRecordVO;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * {@link DetectServiceImpl#getRecord(Long)} 的**归属校验**测试。
 *
 * <p>背景缺陷：原实现的判据是
 * {@code record == null || (userId != null && !userId.equals(record.getUserId()))}。
 * 当 {@code userId} 为空（异步线程、定时任务、将来新增但漏配拦截器的路径）时，
 * 括号内整体为 {@code false}，整个校验被**静默跳过**——变成"人人可读任意 recordId 的
 * 消息原文、推理链与证据"，即典型 IDOR。</p>
 *
 * <p>修复后必须 fail-closed：身份为空一律拒绝，且不存在"公开兜底"。</p>
 *
 * <p>同时锁定 **VIEW 热度去重 key 的身份口径**：必须是登录用户的 {@code user:{id}}，
 * 不能再出现匿名分支（否则未登录流量会共用同一个去重桶）。</p>
 *
 * <p>完全离线：不启动 Spring、不连 MySQL / Redis。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DetectServiceImplRecordOwnershipTest {

    private static final Long RECORD_ID = 100L;
    private static final Long OWNER_ID = 7L;
    private static final Long OTHER_USER_ID = 8L;

    @Mock
    private FactCheckRecordMapper factCheckRecordMapper;
    @Mock
    private AuthorityInfoMapper authorityInfoMapper;
    @Mock
    private EvidenceMapper evidenceMapper;
    @Mock
    private UserFavoriteMapper userFavoriteMapper;
    @Mock
    private RecordRatingMapper recordRatingMapper;
    @Mock
    private HotRankService hotRankService;
    @Mock
    private HotProperties hotProperties;

    @InjectMocks
    private DetectServiceImpl detectServiceImpl;

    @AfterEach
    void clearContext() {
        // BaseContext 是 ThreadLocal：不清理会让后续用例读到上一个用例的身份
        BaseContext.removeCurrentId();
    }

    private FactCheckRecord recordOwnedBy(Long ownerId) {
        return FactCheckRecord.builder()
                .id(RECORD_ID)
                .userId(ownerId)
                .content("网传喝醋可以预防感冒")
                .category("健康")
                .status(2)
                .verdict(2)
                .build();
    }

    /**
     * 正常路径里与"本人状态"无关的打桩：证据列表为空 + 热度去重窗口。
     *
     * <p>注意：**不要**在这里顺手桩 {@code userFavoriteMapper} / {@code recordRatingMapper}——
     * Mockito 是"后打桩覆盖先打桩"，宽泛的 {@code anyLong()} 桩会把用例里的精确桩顶掉
     * （本测试第一版就因此把 favorited=true 断言成了 false）。</p>
     */
    private void stubEvidenceAndHotWindow() {
        when(evidenceMapper.getByRecordId(RECORD_ID)).thenReturn(Collections.emptyList());
        when(hotProperties.getViewDedupMinutes()).thenReturn(30);
    }

    @Test
    @DisplayName("身份为空 → 拒绝（原实现会放行任意 recordId，这是 IDOR）")
    void shouldRejectWhenNotLoggedIn() {
        BaseContext.setCurrentId(null);

        assertThatThrownBy(() -> detectServiceImpl.getRecord(RECORD_ID))
                .isInstanceOf(UserNotLoginException.class);

        // 关键：连库都不该查，更不该回数据
        verify(factCheckRecordMapper, never()).getById(anyLong());
        verify(hotRankService, never()).addScoreOnce(anyLong(), anyDouble(), anyString(), anyInt());
    }

    @Test
    @DisplayName("查别人的记录 → 拒绝（且不泄露'该记录是否存在'以外的信息）")
    void shouldRejectOtherUsersRecord() {
        BaseContext.setCurrentId(OTHER_USER_ID);
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(recordOwnedBy(OWNER_ID));

        assertThatThrownBy(() -> detectServiceImpl.getRecord(RECORD_ID))
                .isInstanceOf(RecordNotFoundException.class);

        verify(evidenceMapper, never()).getByRecordId(anyLong());
        verify(hotRankService, never()).addScoreOnce(anyLong(), anyDouble(), anyString(), anyInt());
    }

    @Test
    @DisplayName("记录不存在 → 与'别人的记录'返回同一异常（避免用错误文案探测存在性）")
    void shouldRejectMissingRecord() {
        BaseContext.setCurrentId(OWNER_ID);
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(null);

        assertThatThrownBy(() -> detectServiceImpl.getRecord(RECORD_ID))
                .isInstanceOf(RecordNotFoundException.class);
    }

    @Test
    @DisplayName("查自己的记录 → 正常返回，且热度去重 key 用登录用户身份")
    void shouldReturnOwnRecordAndScoreWithUserId() {
        BaseContext.setCurrentId(OWNER_ID);
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(recordOwnedBy(OWNER_ID));
        when(userFavoriteMapper.countByUserAndRecord(OWNER_ID, RECORD_ID)).thenReturn(1);
        when(recordRatingMapper.getByUserAndRecord(OWNER_ID, RECORD_ID))
                .thenReturn(RecordRating.builder().id(1L).rating(1).build());
        stubEvidenceAndHotWindow();

        DetectRecordVO vo = detectServiceImpl.getRecord(RECORD_ID);

        assertThat(vo.getId()).isEqualTo(RECORD_ID);
        assertThat(vo.getContent()).isEqualTo("网传喝醋可以预防感冒");
        assertThat(vo.getFavorited()).isTrue();
        assertThat(vo.getMyRating()).isEqualTo(1);

        // 去重 key 必须是 user:{登录用户}，不能是匿名的统一桶
        verify(hotRankService).addScoreOnce(eq(RECORD_ID), anyDouble(), eq("user:" + OWNER_ID), eq(30));
    }
}
