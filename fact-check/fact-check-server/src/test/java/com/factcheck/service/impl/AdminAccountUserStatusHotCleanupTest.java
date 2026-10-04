package com.factcheck.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.factcheck.context.BaseContext;
import com.factcheck.entity.OperationLog;
import com.factcheck.entity.User;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.mapper.OperationLogMapper;
import com.factcheck.mapper.UserMapper;
import com.factcheck.service.HotRankService;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * 用户注销时的**热度榜摘除**契约测试。
 *
 * <p>背景：热度榜是**公开**的 Top10，而产品文档 §4.5 要求"注销用户的记录保留但脱敏展示"。
 * 注销后记录继续占据公开榜单与这个意图相悖（且实测确认 Redis 里会一直留着原始分数，
 * 直到每日 03:00 衰减才可能被清掉）。修复方式是在两条注销路径上显式摘榜。</p>
 *
 * <p>本测试同时钉住两件容易做错的事：</p>
 * <ul>
 *   <li><b>只在注销（status=2）时摘榜</b>：禁用（0）与启用（1）都不该动热度榜；</li>
 *   <li><b>摘榜失败不能影响注销本身</b>：热度是辅助功能，Redis 抖动不该让注销失败。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminAccountUserStatusHotCleanupTest {

    private static final Long ADMIN_ID = 9001L;
    private static final Long TARGET_USER_ID = 66L;

    @Mock
    private UserMapper userMapper;
    @Mock
    private OperationLogMapper operationLogMapper;
    @Mock
    private FactCheckRecordMapper factCheckRecordMapper;
    @Mock
    private HotRankService hotRankService;

    @InjectMocks
    private AdminAccountServiceImpl service;

    @BeforeEach
    void setUpAdmin() {
        BaseContext.setCurrentId(ADMIN_ID);
    }

    @AfterEach
    void clearAdmin() {
        BaseContext.removeCurrentId();
    }

    private User normalUser() {
        return User.builder().id(TARGET_USER_ID).username("demo_user").role(0).status(1).build();
    }

    @Test
    @DisplayName("注销用户（status=2）→ 把 TA 的记录从热度榜摘除")
    void cancellingUserShouldDetachRecordsFromHotRank() {
        when(userMapper.getById(TARGET_USER_ID)).thenReturn(normalUser());
        when(factCheckRecordMapper.listIdsByUser(TARGET_USER_ID)).thenReturn(Arrays.asList(11L, 12L, 13L));
        when(hotRankService.remove(any(Long[].class))).thenReturn(3L);

        service.updateUserStatus(TARGET_USER_ID, 2);

        // 注意 Long... 是可变参数：Mockito 会把实参**展平**捕获，
        // 因此要断言 getAllValues() 而不是 getValue()（后者会 ClassCast）。
        ArgumentCaptor<Long> ids = ArgumentCaptor.forClass(Long.class);
        verify(hotRankService).remove(ids.capture());
        assertThat(ids.getAllValues()).containsExactly(11L, 12L, 13L);
        verify(userMapper).update(any(User.class));
    }

    @Test
    @DisplayName("禁用（status=0）与启用（status=1）都不该动热度榜")
    void nonCancellingStatusShouldNotTouchHotRank() {
        when(userMapper.getById(TARGET_USER_ID)).thenReturn(normalUser());

        service.updateUserStatus(TARGET_USER_ID, 0);
        service.updateUserStatus(TARGET_USER_ID, 1);

        verify(hotRankService, never()).remove(any(Long[].class));
        verify(factCheckRecordMapper, never()).listIdsByUser(anyLong());
    }

    @Test
    @DisplayName("用户没有记录 → 不调用摘榜（避免 listByIds 那种空集合 SQL）")
    void userWithoutRecordsShouldSkipHotRemoval() {
        when(userMapper.getById(TARGET_USER_ID)).thenReturn(normalUser());
        when(factCheckRecordMapper.listIdsByUser(TARGET_USER_ID)).thenReturn(Collections.emptyList());

        service.updateUserStatus(TARGET_USER_ID, 2);

        verify(hotRankService, never()).remove(any(Long[].class));
        // 注销本身仍必须成功
        verify(userMapper).update(any(User.class));
    }

    @Test
    @DisplayName("剥离热度失败（Redis 抖动）→ 注销仍然成功，且不影响操作日志")
    void hotRemovalFailureShouldNotBreakCancellation() {
        when(userMapper.getById(TARGET_USER_ID)).thenReturn(normalUser());
        when(factCheckRecordMapper.listIdsByUser(TARGET_USER_ID)).thenReturn(Arrays.asList(11L));
        when(hotRankService.remove(any(Long[].class)))
                .thenThrow(new RuntimeException("redis down"));

        service.updateUserStatus(TARGET_USER_ID, 2);

        verify(userMapper).update(any(User.class));
        verify(operationLogMapper).insert(any(OperationLog.class));
    }

    @Test
    @DisplayName("操作日志按状态区分：0=禁用、1=启用、2=注销（原先 2 被记成“禁用用户”）")
    void operationLogShouldDistinguishStatus() {
        when(userMapper.getById(TARGET_USER_ID)).thenReturn(normalUser());
        when(factCheckRecordMapper.listIdsByUser(TARGET_USER_ID)).thenReturn(Collections.emptyList());

        service.updateUserStatus(TARGET_USER_ID, 0);
        service.updateUserStatus(TARGET_USER_ID, 2);

        ArgumentCaptor<OperationLog> logs = ArgumentCaptor.forClass(OperationLog.class);
        verify(operationLogMapper, org.mockito.Mockito.times(2)).insert(logs.capture());
        assertThat(logs.getAllValues().get(0).getOperation()).isEqualTo("禁用用户");
        assertThat(logs.getAllValues().get(1).getOperation()).isEqualTo("注销用户");
    }
}
