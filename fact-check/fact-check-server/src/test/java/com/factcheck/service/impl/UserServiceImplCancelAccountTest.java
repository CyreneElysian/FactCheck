package com.factcheck.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.factcheck.context.BaseContext;
import com.factcheck.exception.UserNotLoginException;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.mapper.OperationLogMapper;
import com.factcheck.mapper.UserMapper;
import com.factcheck.properties.WeChatProperties;
import com.factcheck.service.HotRankService;
import com.factcheck.service.RateLimitService;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 用户**自助注销**（`POST /user/cancel`）时的热度榜摘除契约测试。
 *
 * <p>与管理员注销路径（{@code AdminAccountServiceImpl.updateUserStatus(status=2)}）保持一致：
 * 热度榜是公开 Top10，注销用户的记录不应继续出现在里面。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceImplCancelAccountTest {

    private static final Long USER_ID = 66L;

    @Mock
    private UserMapper userMapper;
    @Mock
    private WeChatProperties weChatProperties;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private OperationLogMapper operationLogMapper;
    @Mock
    private FactCheckRecordMapper factCheckRecordMapper;
    @Mock
    private HotRankService hotRankService;

    @InjectMocks
    private UserServiceImpl service;

    @AfterEach
    void clearContext() {
        BaseContext.removeCurrentId();
    }

    @Test
    @DisplayName("自助注销 → 注销落库 + 记录从热度榜摘除")
    void cancelAccountShouldDetachRecordsFromHotRank() {
        BaseContext.setCurrentId(USER_ID);
        when(factCheckRecordMapper.listIdsByUser(USER_ID)).thenReturn(Arrays.asList(21L, 22L));
        when(hotRankService.remove(any(Long[].class))).thenReturn(2L);

        service.cancelAccount();

        verify(userMapper).cancel(USER_ID);

        // Long... 是可变参数，Mockito 会展平捕获，因此断言 getAllValues()
        ArgumentCaptor<Long> ids = ArgumentCaptor.forClass(Long.class);
        verify(hotRankService).remove(ids.capture());
        assertThat(ids.getAllValues()).containsExactly(21L, 22L);
    }

    @Test
    @DisplayName("未登录 → 拒绝，且不摘榜、不注销")
    void cancelAccountShouldRejectAnonymous() {
        BaseContext.setCurrentId(null);

        assertThatThrownBy(() -> service.cancelAccount())
                .isInstanceOf(UserNotLoginException.class);

        verify(userMapper, never()).cancel(anyLong());
        verify(hotRankService, never()).remove(any(Long[].class));
    }

    @Test
    @DisplayName("注销本身必须先成功：即使没有任何记录也不报错")
    void cancelAccountShouldWorkWithoutRecords() {
        BaseContext.setCurrentId(USER_ID);
        when(factCheckRecordMapper.listIdsByUser(USER_ID)).thenReturn(Collections.emptyList());

        service.cancelAccount();

        verify(userMapper).cancel(USER_ID);
        verify(hotRankService, never()).remove(any(Long[].class));
    }

    @Test
    @DisplayName("摘榜失败（Redis 抖动）→ 注销仍然成功")
    void hotRemovalFailureShouldNotBreakSelfCancellation() {
        BaseContext.setCurrentId(USER_ID);
        when(factCheckRecordMapper.listIdsByUser(USER_ID)).thenReturn(Arrays.asList(21L));
        when(hotRankService.remove(any(Long[].class)))
                .thenThrow(new RuntimeException("redis down"));

        service.cancelAccount();

        verify(userMapper).cancel(USER_ID);
    }
}
