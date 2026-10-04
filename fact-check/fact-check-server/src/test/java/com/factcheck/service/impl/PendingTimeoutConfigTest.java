package com.factcheck.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.factcheck.dto.DetectSubmitDTO;
import com.factcheck.entity.FactCheckRecord;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.properties.AgentProperties;
import com.factcheck.properties.PendingProperties;
import com.factcheck.service.AgentClient;
import com.factcheck.service.ContentSafetyService;
import com.factcheck.service.RateLimitService;
import com.factcheck.context.BaseContext;
import com.factcheck.mapper.AuthorityInfoMapper;
import com.factcheck.mapper.DetectLogMapper;
import com.factcheck.service.HotRankService;
import com.factcheck.service.DetectService;
import java.time.LocalDateTime;
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
 * 待诊断处理时限可配置（S14）的契约测试。
 *
 * <h3>为什么要测这一条</h3>
 * 产品文档 S14 写的是「待诊断记录默认处理时限 48 小时（**配置项可调**）」，
 * 而原实现把它**硬编码**在 {@code DetectServiceImpl}：
 * <pre>
 * private static final int PENDING_HOURS = 48;
 * </pre>
 * 即文档承诺了一个**不存在**的能力 —— 部署方按文档去改配置会发现无处可改，
 * 且不会有任何报错。这类"文档说能配、实际不能配"的缺陷只能靠
 * 「真的改配置 → 断言 deadline 跟着变」来守住。
 *
 * <p>同时守住下限保护：配置成 0 或负数时归一化为 1 小时并打 WARN。
 * 这个错误不抛异常，只表现为"所有待诊断记录一进队列就显示已超时"，
 * 把提示变成噪音，很难反查。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PendingTimeoutConfigTest {

    private static final Long USER_ID = 1L;
    private static final String CONTENT = "世界卫生组织已将熬夜列为2A类致癌物";

    @Mock
    private FactCheckRecordMapper factCheckRecordMapper;
    @Mock
    private AuthorityInfoMapper authorityInfoMapper;
    @Mock
    private DetectLogMapper detectLogMapper;
    @Mock
    private AgentClient agentClient;
    @Mock
    private ContentSafetyService contentSafetyService;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private HotRankService hotRankService;
    /**
     * 自身接口的引用：`submit` 通过它委派异步检测（self-injection，
     * 直接调用本类方法会绕过 @Async 代理）
     */
    @Mock
    private DetectService detectService;
    @Mock
    private AgentProperties agentProperties;
    @Mock
    private PendingProperties pendingProperties;

    @InjectMocks
    private DetectServiceImpl detectServiceImpl;

    /** 走"新建记录"分支：精确去重关闭、未命中权威信息，直接把新记录落库 */
    private void stubNewRecordPath() {
        when(agentProperties.isExactEnabled()).thenReturn(false);
        when(agentProperties.isSemanticEnabled()).thenReturn(false);
        // 权威信息匹配：返回空列表（本测试不关心该分支）
        when(authorityInfoMapper.listPublishedForMatch()).thenReturn(java.util.Collections.emptyList());
    }

    private LocalDateTime capturedDeadline() {
        ArgumentCaptor<FactCheckRecord> captor = ArgumentCaptor.forClass(FactCheckRecord.class);
        org.mockito.Mockito.verify(factCheckRecordMapper).insert(captor.capture());
        return captor.getValue().getDeadline();
    }

    /**
     * `submit` 会先校验登录态（BaseContext 由拦截器填充），
     * 纯单测里没有拦截器，所以要自己放进 ThreadLocal 并在用完后清掉
     * —— 否则会串到同线程的其它测试，造成难以复现的偶发失败。
     */
    private void submit() {
        BaseContext.setCurrentId(USER_ID);
        try {
            DetectSubmitDTO dto = new DetectSubmitDTO();
            dto.setContent(CONTENT);
            dto.setCategory("健康");
            dto.setSourceType(1);
            detectServiceImpl.submit(dto);
        } finally {
            BaseContext.removeCurrentId();
        }
    }

    @Test
    @DisplayName("默认 48 小时：deadline ≈ now + 48h")
    void defaultTimeoutIsUsed() {
        stubNewRecordPath();
        when(pendingProperties.getTimeoutHours()).thenReturn(48);

        submit();

        LocalDateTime expected = LocalDateTime.now().plusHours(48);
        assertThat(capturedDeadline())
                .isAfter(expected.minusMinutes(2))
                .isBefore(expected.plusMinutes(2));
    }

    @Test
    @DisplayName("★ 改成 6 小时必须真的生效（这条就是「文档说可配、实际不能配」的回归测试）")
    void configuredTimeoutTakesEffect() {
        stubNewRecordPath();
        when(pendingProperties.getTimeoutHours()).thenReturn(6);

        submit();

        LocalDateTime deadline = capturedDeadline();
        LocalDateTime expected6h = LocalDateTime.now().plusHours(6);
        LocalDateTime expected48h = LocalDateTime.now().plusHours(48);

        assertThat(deadline)
                .as("配置成 6 小时时，deadline 应贴近 now+6h")
                .isAfter(expected6h.minusMinutes(2))
                .isBefore(expected6h.plusMinutes(2));
        assertThat(deadline)
                .as("绝不能仍是硬编码的 48 小时")
                .isBefore(expected48h.minusHours(1));
    }

    @Test
    @DisplayName("配置为 0 时归一化为 1 小时（不能变成「一进队列就超时」）")
    void zeroIsNormalizedToOneHour() {
        stubNewRecordPath();
        when(pendingProperties.getTimeoutHours()).thenReturn(0);

        submit();

        LocalDateTime deadline = capturedDeadline();
        assertThat(deadline)
                .as("0 应被归一化为 1 小时，而不是让 deadline 落在过去")
                .isAfter(LocalDateTime.now().plusMinutes(55))
                .isBefore(LocalDateTime.now().plusHours(2));
    }

    @Test
    @DisplayName("配置为负数时同样归一化为 1 小时")
    void negativeIsNormalizedToOneHour() {
        stubNewRecordPath();
        when(pendingProperties.getTimeoutHours()).thenReturn(-5);

        submit();

        assertThat(capturedDeadline()).isAfter(LocalDateTime.now().plusMinutes(55));
    }

    @Test
    @DisplayName("时限只影响新建记录：已有记录不因改配置而被改写 deadline")
    void existingRecordDeadlineUntouched() {
        // 精确去重命中且记录已完成 → 走 handleExactHit 的复用分支，不新建、不改 deadline
        stubNewRecordPath();
        when(agentProperties.isExactEnabled()).thenReturn(true);

        FactCheckRecord exist = new FactCheckRecord();
        exist.setId(99L);
        exist.setUserId(USER_ID);
        exist.setStatus(2);
        exist.setCategory("健康");
        when(factCheckRecordMapper.getByContentHash(anyString())).thenReturn(exist);

        submit();

        org.mockito.Mockito.verify(factCheckRecordMapper, org.mockito.Mockito.never())
                .insert(any(FactCheckRecord.class));
    }
}
