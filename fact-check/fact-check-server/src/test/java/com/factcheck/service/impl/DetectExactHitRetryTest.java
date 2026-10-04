package com.factcheck.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.factcheck.entity.DetectLog;
import com.factcheck.entity.FactCheckRecord;
import com.factcheck.mapper.DetectLogMapper;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.properties.AgentProperties;
import com.factcheck.service.DetectService;
import com.factcheck.service.HotRankService;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 精确去重命中后的分支契约测试。
 *
 * <h3>为什么值得单独测</h3>
 * 检测结果被去重**缓存**：同一内容再次提交时直接返回旧记录、不再调 Agent。
 * 这在正常情况下是特性，但"卡在检测中"的记录会因此**无法自救**：
 * <pre>
 * 后端崩溃/重启 → 异步任务（只在内存里）消失 → 记录永远停在 status=0
 * 用户重提同一句 → 精确去重命中同一行 → 返回 → 还是"检测中"，永远出不来
 * </pre>
 * 所以这里要钉住两条：
 * <ol>
 *   <li>{@code status=3}（失败）→ 无条件重跑；</li>
 *   <li>{@code status=0}（检测中）且**卡死超过阈值** → 原子认领后重跑；没超时就老实返回，
 *       否则"用户连点两次提交"会变成两次真实 Agent 调用。</li>
 * </ol>
 *
 * <p>本测试通过反射调用私有的 {@code handleExactHit}：它是这段分支逻辑的唯一归属地，
 * 而公有的 {@code submit} 依赖过重（内容安全、限流、图片校验等），
 * 为了这几条分支去搭整套桩会掩盖真正要验证的东西。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DetectExactHitRetryTest {

    private static final Long RECORD_ID = 27L;
    private static final Long USER_ID = 1L;
    private static final String CONTENT = "世界卫生组织已将熬夜列为2A类致癌物，所以长期熬夜一定会得癌症。";
    private static final String HASH = "abc123";

    @Mock
    private FactCheckRecordMapper factCheckRecordMapper;
    @Mock
    private DetectLogMapper detectLogMapper;
    @Mock
    private HotRankService hotRankService;
    @Mock
    private AgentProperties agentProperties;
    @Mock
    private DetectService detectService;

    @InjectMocks
    private DetectServiceImpl detectServiceImpl;

    private FactCheckRecord record(Integer status) {
        FactCheckRecord r = new FactCheckRecord();
        r.setId(RECORD_ID);
        r.setUserId(USER_ID);
        r.setStatus(status);
        r.setContent(CONTENT);
        return r;
    }

    private Long invokeHandleExactHit(FactCheckRecord exist) throws Exception {
        Method m = DetectServiceImpl.class.getDeclaredMethod(
                "handleExactHit", FactCheckRecord.class, Long.class, String.class, Integer.class,
                String.class, String.class, String.class);
        m.setAccessible(true);
        return (Long) m.invoke(detectServiceImpl, exist, USER_ID, "其他", 1, null, CONTENT, HASH);
    }

    @Test
    @DisplayName("status=3（失败）→ 重置为检测中并重跑 Agent")
    void failedRecordShouldBeRetried() throws Exception {
        when(agentProperties.getDetectingStaleMinutes()).thenReturn(10);

        Long id = invokeHandleExactHit(record(3));

        assertThat(id).isEqualTo(RECORD_ID);
        verify(factCheckRecordMapper).resetToDetecting(RECORD_ID);
        verify(detectService).asyncDetect(RECORD_ID, CONTENT);
        // 重跑路径不该再累加命中次数（那是"复用已有结论"才做的事）
        verify(factCheckRecordMapper, never()).updateHitCount(any());
    }

    @Test
    @DisplayName("status=0 且已卡死超阈值 → 原子认领成功后重跑（这就是卡死自救）")
    void staleDetectingRecordShouldBeReclaimed() throws Exception {
        when(agentProperties.getDetectingStaleMinutes()).thenReturn(10);
        // 认领成功
        when(factCheckRecordMapper.resetStaleDetecting(eq(RECORD_ID), any(LocalDateTime.class)))
                .thenReturn(1);

        Long id = invokeHandleExactHit(record(0));

        assertThat(id).isEqualTo(RECORD_ID);
        verify(factCheckRecordMapper).resetStaleDetecting(eq(RECORD_ID), any(LocalDateTime.class));
        verify(detectService).asyncDetect(RECORD_ID, CONTENT);
        verify(factCheckRecordMapper, never()).updateHitCount(any());
    }

    @Test
    @DisplayName("status=0 但还没超阈值 → 不重跑，只当作正常去重命中（防止连点两次=两次 Agent 调用）")
    void freshDetectingRecordShouldNotBeReclaimed() throws Exception {
        when(agentProperties.getDetectingStaleMinutes()).thenReturn(10);
        // 认领失败：还没到阈值
        when(factCheckRecordMapper.resetStaleDetecting(eq(RECORD_ID), any(LocalDateTime.class)))
                .thenReturn(0);

        Long id = invokeHandleExactHit(record(0));

        assertThat(id).isEqualTo(RECORD_ID);
        verify(detectService, never()).asyncDetect(any(), anyString());
        // 走的是"复用已有结论"分支
        verify(factCheckRecordMapper).updateHitCount(RECORD_ID);
    }

    @Test
    @DisplayName("status=2（已完成）→ 直接复用结论，绝不碰 Agent")
    void completedRecordShouldReuseConclusion() throws Exception {
        Long id = invokeHandleExactHit(record(2));

        assertThat(id).isEqualTo(RECORD_ID);
        verify(detectService, never()).asyncDetect(any(), anyString());
        verify(factCheckRecordMapper).updateHitCount(RECORD_ID);
        verify(factCheckRecordMapper, never()).resetToDetecting(any());
    }

    @Test
    @DisplayName("status=1（待人工）→ 同样复用，不重跑")
    void pendingHumanRecordShouldReuse() throws Exception {
        Long id = invokeHandleExactHit(record(1));

        assertThat(id).isEqualTo(RECORD_ID);
        verify(detectService, never()).asyncDetect(any(), anyString());
        verify(factCheckRecordMapper).updateHitCount(RECORD_ID);
    }

    @Test
    @DisplayName("status 为 null 时按复用处理，不能抛异常")
    void nullStatusShouldReuse() throws Exception {
        Long id = invokeHandleExactHit(record(null));

        assertThat(id).isEqualTo(RECORD_ID);
        verify(detectService, never()).asyncDetect(any(), anyString());
        verify(factCheckRecordMapper).updateHitCount(RECORD_ID);
    }

    @Test
    @DisplayName("重跑路径会写一条 dedupHit=0 的检测日志（区别于命中缓存的 dedupHit=1）")
    void retryPathShouldLogAsNotDedupHit() throws Exception {
        when(agentProperties.getDetectingStaleMinutes()).thenReturn(10);
        when(factCheckRecordMapper.resetStaleDetecting(eq(RECORD_ID), any(LocalDateTime.class)))
                .thenReturn(1);

        invokeHandleExactHit(record(0));

        verify(detectLogMapper).insert(org.mockito.ArgumentMatchers.argThat(
                (DetectLog logEntry) -> logEntry.getDedupHit() != null && logEntry.getDedupHit() == 0));
    }
}
