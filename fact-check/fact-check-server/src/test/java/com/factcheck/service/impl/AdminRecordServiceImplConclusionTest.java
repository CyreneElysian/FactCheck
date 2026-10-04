package com.factcheck.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.factcheck.context.BaseContext;
import com.factcheck.dto.AdminConclusionDTO;
import com.factcheck.entity.ConclusionRevision;
import com.factcheck.entity.FactCheckRecord;
import com.factcheck.entity.Message;
import com.factcheck.entity.OperationLog;
import com.factcheck.exception.BaseException;
import com.factcheck.handler.DetectWebSocketHandler;
import com.factcheck.mapper.AuthorityInfoMapper;
import com.factcheck.mapper.ConclusionRevisionMapper;
import com.factcheck.mapper.EvidenceMapper;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.mapper.MessageMapper;
import com.factcheck.mapper.OperationLogMapper;
import com.factcheck.service.AgentClient;
import com.factcheck.service.HotRankService;
import java.time.LocalDateTime;
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
 * {@link AdminRecordServiceImpl} 的人工诊断与改结论契约测试。
 *
 * <p>这两个方法是**全项目最大的测试盲区之一**（审计报告点名），
 * 同时它们承载"结论可追溯"这条产品承诺。第 4 批把它们从
 * "先查状态、再无条件下 update" 改为**带前置状态条件的条件更新 + SQL 侧原子 version+1**，
 * 本测试把新的语义钉住：</p>
 *
 * <ul>
 *   <li><b>并发守卫</b>：条件更新影响 0 行（说明记录状态已被别人改过）→ 抛业务异常，
 *       且**不再发站内信、不再写操作日志**，也不会产生"快照写了、结论没改"的脏数据；</li>
 *   <li><b>版本号</b>：不再由 Java 计算（原先 {@code version == null ? 2 : version + 1}
 *       在 version 恒不为 null 的情况下会把首次人工诊断从 1 推到 3），
 *       改由 SQL 的 {@code version = version + 1} 保证恒 +1；</li>
 *   <li><b>参数传递</b>：判结论与推理依据必须原样传下去，状态条件必须是正确的期望值
 *       （诊断要求 1=待诊断，改结论要求 2=已完成）。</li>
 * </ul>
 *
 * <p>完全离线：mapper 全部 Mockito mock，不启动 Spring、不连 MySQL/Redis。
 * 事务与真实行锁行为由 {@code DetectResultWriterTransactionIT}（真实 MySQL）覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminRecordServiceImplConclusionTest {

    private static final Long ADMIN_ID = 9001L;
    private static final Long RECORD_ID = 777L;
    private static final Long USER_ID = 66L;

    @Mock
    private FactCheckRecordMapper factCheckRecordMapper;
    @Mock
    private AuthorityInfoMapper authorityInfoMapper;
    @Mock
    private EvidenceMapper evidenceMapper;
    @Mock
    private ConclusionRevisionMapper conclusionRevisionMapper;
    @Mock
    private MessageMapper messageMapper;
    @Mock
    private OperationLogMapper operationLogMapper;
    @Mock
    private AgentClient agentClient;
    @Mock
    private HotRankService hotRankService;
    @Mock
    private DetectWebSocketHandler detectWebSocketHandler;

    @InjectMocks
    private AdminRecordServiceImpl adminRecordService;

    @BeforeEach
    void setUpAdmin() {
        BaseContext.setCurrentId(ADMIN_ID);
    }

    @AfterEach
    void clearAdmin() {
        BaseContext.removeCurrentId();
    }

    private FactCheckRecord record(int status) {
        return FactCheckRecord.builder()
                .id(RECORD_ID)
                .userId(USER_ID)
                .content("网传喝醋可以预防感冒")
                .category("健康")
                .status(status)
                .verdict(2)
                .verdictSource(1)
                .reasoningChain("旧的推理链")
                .version(1)
                .hitCount(1)
                .build();
    }

    private AdminConclusionDTO conclusion(Integer verdict, String chain, String reason) {
        AdminConclusionDTO dto = new AdminConclusionDTO();
        dto.setVerdict(verdict);
        dto.setReasoningChain(chain);
        dto.setReason(chain == null ? null : reason);
        return dto;
    }

    // ------------------------------------------------------------------
    // submitConclusion（人工诊断）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("人工诊断成功：条件更新 expectedStatus=1、status→2、verdictSource=2，并发站内信与日志")
    void submitConclusionShouldUseConditionalUpdate() {
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(record(1));
        when(factCheckRecordMapper.updateConclusionIfStatus(
                anyLong(), anyInt(), any(), any(), any(), anyString(), any())).thenReturn(1);
        AdminConclusionDTO dto = conclusion(2, "经人工核实为谣言", null);

        adminRecordService.submitConclusion(RECORD_ID, dto);

        ArgumentCaptor<Integer> expected = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> target = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> verdict = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> chain = ArgumentCaptor.forClass(String.class);
        verify(factCheckRecordMapper).updateConclusionIfStatus(
                eq(RECORD_ID), expected.capture(), target.capture(), verdict.capture(),
                eq(2), chain.capture(), any(LocalDateTime.class));

        assertThat(expected.getValue()).as("诊断必须在“待诊断(1)”状态上做条件更新").isEqualTo(1);
        assertThat(target.getValue()).as("诊断后状态应置为已完成(2)").isEqualTo(2);
        assertThat(verdict.getValue()).isEqualTo(2);
        assertThat(chain.getValue()).isEqualTo("经人工核实为谣言");

        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper).insert(message.capture());
        assertThat(message.getValue().getType()).isEqualTo(3);
        assertThat(message.getValue().getUserId()).isEqualTo(USER_ID);
        verify(operationLogMapper).insert(any(OperationLog.class));
    }

    @Test
    @DisplayName("并发守卫：人工诊断条件更新影响 0 行 → 抛业务异常，不发站内信、不写日志")
    void submitConclusionShouldFailWhenNoRowAffected() {
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(record(1));
        when(factCheckRecordMapper.updateConclusionIfStatus(
                anyLong(), anyInt(), any(), any(), any(), anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> adminRecordService.submitConclusion(
                RECORD_ID, conclusion(2, "经人工核实为谣言", null)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("已被其他管理员处理");

        verify(messageMapper, never()).insert(any(Message.class));
        verify(operationLogMapper, never()).insert(any(OperationLog.class));
    }

    @Test
    @DisplayName("人工诊断：记录不在待诊断状态 → 直接拒绝，连条件更新都不发")
    void submitConclusionShouldRejectWhenStatusIsNotPending() {
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(record(2));

        assertThatThrownBy(() -> adminRecordService.submitConclusion(
                RECORD_ID, conclusion(2, "经人工核实为谣言", null)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("不在待诊断状态");

        verify(factCheckRecordMapper, never()).updateConclusionIfStatus(
                anyLong(), anyInt(), any(), any(), any(), anyString(), any());
    }

    // ------------------------------------------------------------------
    // updateConclusion（修改历史结论）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("改结论成功：条件更新 expectedStatus=2 且不改状态；版本号交由 SQL 自增（不再由 Java 计算）")
    void updateConclusionShouldUseConditionalUpdateWithoutChangingStatus() {
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(record(2));
        when(factCheckRecordMapper.updateConclusionIfStatus(
                anyLong(), anyInt(), any(), any(), any(), anyString(), any())).thenReturn(1);

        adminRecordService.updateConclusion(RECORD_ID, conclusion(1, "新的推理链", "原结论有误"));

        ArgumentCaptor<Integer> expected = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> target = ArgumentCaptor.forClass(Integer.class);
        verify(factCheckRecordMapper).updateConclusionIfStatus(
                eq(RECORD_ID), expected.capture(), target.capture(), eq(1), eq(2), eq("新的推理链"), eq(null));

        assertThat(expected.getValue()).as("改结论必须在“已完成(2)”状态上做条件更新").isEqualTo(2);
        assertThat(target.getValue()).as("改结论不改变状态，应为 null 表示不出现在 update set 中").isNull();

        // 版本快照必须写，且带上修改原因
        ArgumentCaptor<ConclusionRevision> revision = ArgumentCaptor.forClass(ConclusionRevision.class);
        verify(conclusionRevisionMapper).insert(revision.capture());
        assertThat(revision.getValue().getOldVerdict()).isEqualTo(2);
        assertThat(revision.getValue().getNewVerdict()).isEqualTo(1);
        assertThat(revision.getValue().getReason()).isEqualTo("原结论有误");
        assertThat(revision.getValue().getSource()).isEqualTo(1);

        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper).insert(message.capture());
        assertThat(message.getValue().getType()).isEqualTo(5);
    }

    @Test
    @DisplayName("并发守卫：改结论条件更新影响 0 行 → 抛业务异常并回滚快照（交由 @Transactional）")
    void updateConclusionShouldFailWhenNoRowAffected() {
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(record(2));
        when(factCheckRecordMapper.updateConclusionIfStatus(
                anyLong(), anyInt(), any(), any(), any(), anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> adminRecordService.updateConclusion(
                RECORD_ID, conclusion(1, "新的推理链", "原结论有误")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("状态已变化");

        verify(messageMapper, never()).insert(any(Message.class));
        verify(operationLogMapper, never()).insert(any(OperationLog.class));
    }

    @Test
    @DisplayName("改结论：记录未完成（status != 2）→ 拒绝")
    void updateConclusionShouldRejectUnfinishedRecord() {
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(record(1));

        assertThatThrownBy(() -> adminRecordService.updateConclusion(
                RECORD_ID, conclusion(1, "新的推理链", "原结论有误")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("只有已完成的记录");

        verify(conclusionRevisionMapper, never()).insert(any(ConclusionRevision.class));
    }
}
