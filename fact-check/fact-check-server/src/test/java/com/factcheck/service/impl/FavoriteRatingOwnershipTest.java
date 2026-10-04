package com.factcheck.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.factcheck.context.BaseContext;
import com.factcheck.dto.RatingSubmitDTO;
import com.factcheck.entity.FactCheckRecord;
import com.factcheck.exception.RecordNotFoundException;
import com.factcheck.exception.UserNotLoginException;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.mapper.RecordRatingMapper;
import com.factcheck.mapper.UserFavoriteMapper;
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
 * 收藏与评分的**归属校验**测试。
 *
 * <p>背景缺陷：{@link FavoriteServiceImpl#add(Long)} 与 {@link RatingServiceImpl#submit}
 * 原先对目标记录只判 {@code record == null}，没有校验归属。于是任意登录用户都能：
 * <ul>
 *   <li>收藏他人的检测记录——收藏列表会因此出现别人的消息原文与结论（信息泄露）；</li>
 *   <li>给他人记录刷"有帮助 / 无帮助"——评分是模型优化的反馈信号，被外部刷入直接污染数据。</li>
 * </ul>
 * 同仓的 {@code AppealServiceImpl}、{@code DetectServiceImpl.getRecord} 都做了归属校验，
 * 这两处是漏的。</p>
 *
 * <p>完全离线：不启动 Spring、不连 MySQL / Redis。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FavoriteRatingOwnershipTest {

    private static final Long RECORD_ID = 100L;
    private static final Long OWNER_ID = 7L;
    private static final Long OTHER_USER_ID = 8L;

    @Mock
    private UserFavoriteMapper userFavoriteMapper;
    @Mock
    private FactCheckRecordMapper factCheckRecordMapper;
    @Mock
    private RecordRatingMapper recordRatingMapper;

    @InjectMocks
    private FavoriteServiceImpl favoriteService;
    @InjectMocks
    private RatingServiceImpl ratingService;

    @AfterEach
    void clearContext() {
        BaseContext.removeCurrentId();
    }

    private FactCheckRecord recordOwnedBy(Long ownerId) {
        return FactCheckRecord.builder()
                .id(RECORD_ID)
                .userId(ownerId)
                .content("网传某地发生地震")
                .status(2)
                .verdict(2)
                .build();
    }

    private RatingSubmitDTO rating(Integer value) {
        RatingSubmitDTO dto = new RatingSubmitDTO();
        dto.setRecordId(RECORD_ID);
        dto.setRating(value);
        return dto;
    }

    // ---------------- 收藏 ----------------

    @Test
    @DisplayName("收藏：别人的记录 → 拒绝（原先可收藏，列表里会出现他人内容）")
    void addShouldRejectOtherUsersRecord() {
        BaseContext.setCurrentId(OTHER_USER_ID);
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(recordOwnedBy(OWNER_ID));

        assertThatThrownBy(() -> favoriteService.add(RECORD_ID))
                .isInstanceOf(RecordNotFoundException.class);

        verify(userFavoriteMapper, never()).insert(any());
    }

    @Test
    @DisplayName("收藏：记录不存在 → 拒绝")
    void addShouldRejectMissingRecord() {
        BaseContext.setCurrentId(OWNER_ID);
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(null);

        assertThatThrownBy(() -> favoriteService.add(RECORD_ID))
                .isInstanceOf(RecordNotFoundException.class);

        verify(userFavoriteMapper, never()).insert(any());
    }

    @Test
    @DisplayName("收藏：未登录 → 拒绝")
    void addShouldRejectAnonymous() {
        BaseContext.setCurrentId(null);

        assertThatThrownBy(() -> favoriteService.add(RECORD_ID))
                .isInstanceOf(UserNotLoginException.class);

        verify(factCheckRecordMapper, never()).getById(anyLong());
    }

    @Test
    @DisplayName("收藏：自己的记录 → 正常写入")
    void addShouldAcceptOwnRecord() {
        BaseContext.setCurrentId(OWNER_ID);
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(recordOwnedBy(OWNER_ID));
        when(userFavoriteMapper.countByUserAndRecord(OWNER_ID, RECORD_ID)).thenReturn(0);

        favoriteService.add(RECORD_ID);

        verify(userFavoriteMapper).insert(any());
    }

    // ---------------- 评分 ----------------

    @Test
    @DisplayName("评分：别人的记录 → 拒绝（评分是模型反馈信号，不能由外部刷入）")
    void submitShouldRejectOtherUsersRecord() {
        BaseContext.setCurrentId(OTHER_USER_ID);
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(recordOwnedBy(OWNER_ID));

        assertThatThrownBy(() -> ratingService.submit(rating(1)))
                .isInstanceOf(RecordNotFoundException.class);

        verify(recordRatingMapper, never()).insert(any());
        verify(recordRatingMapper, never()).updateRating(anyLong(), any());
    }

    @Test
    @DisplayName("评分：记录不存在 → 拒绝")
    void submitShouldRejectMissingRecord() {
        BaseContext.setCurrentId(OWNER_ID);
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(null);

        assertThatThrownBy(() -> ratingService.submit(rating(0)))
                .isInstanceOf(RecordNotFoundException.class);

        verify(recordRatingMapper, never()).insert(any());
    }

    @Test
    @DisplayName("评分：未登录 → 拒绝")
    void submitShouldRejectAnonymous() {
        BaseContext.setCurrentId(null);

        assertThatThrownBy(() -> ratingService.submit(rating(1)))
                .isInstanceOf(UserNotLoginException.class);

        verify(factCheckRecordMapper, never()).getById(anyLong());
    }

    @Test
    @DisplayName("评分：自己的记录首次评分 → 新增")
    void submitShouldInsertFirstRating() {
        BaseContext.setCurrentId(OWNER_ID);
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(recordOwnedBy(OWNER_ID));
        when(recordRatingMapper.getByUserAndRecord(OWNER_ID, RECORD_ID)).thenReturn(null);

        ratingService.submit(rating(1));

        verify(recordRatingMapper).insert(any());
    }

    @Test
    @DisplayName("评分：自己的记录重复评分 → 更新而非新增")
    void submitShouldUpdateExistingRating() {
        BaseContext.setCurrentId(OWNER_ID);
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(recordOwnedBy(OWNER_ID));
        when(recordRatingMapper.getByUserAndRecord(OWNER_ID, RECORD_ID))
                .thenReturn(com.factcheck.entity.RecordRating.builder().id(55L).rating(1).build());

        ratingService.submit(rating(0));

        verify(recordRatingMapper, never()).insert(any());
        verify(recordRatingMapper).updateRating(55L, 0);
    }
}
