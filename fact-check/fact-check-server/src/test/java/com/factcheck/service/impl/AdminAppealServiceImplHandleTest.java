package com.factcheck.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.factcheck.constant.MessageConstant;
import com.factcheck.context.BaseContext;
import com.factcheck.dto.AdminAppealHandleDTO;
import com.factcheck.entity.Appeal;
import com.factcheck.entity.ConclusionRevision;
import com.factcheck.entity.FactCheckRecord;
import com.factcheck.entity.Message;
import com.factcheck.entity.OperationLog;
import com.factcheck.exception.AppealNotFoundException;
import com.factcheck.exception.BaseException;
import com.factcheck.exception.RecordNotFoundException;
import com.factcheck.mapper.AppealMapper;
import com.factcheck.mapper.ConclusionRevisionMapper;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.mapper.MessageMapper;
import com.factcheck.mapper.OperationLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link AdminAppealServiceImpl} 的“采纳/驳回申诉”契约测试。
 *
 * <p>背景：前端曾漏传 newVerdict / newReasoningChain，导致管理员点“采纳”必然失败。
 * 后端把“采纳必须带新结论 + 新推理依据”作为硬校验（handleAccept 里的两个前置判断）。
 * 本测试把这个契约钉死：</p>
 * <ul>
 *   <li>采纳：newVerdict 为空或不在 {1,2,3} 内 → 抛 BaseException（消息含“新结论”）；</li>
 *   <li>采纳：newReasoningChain 为 null/空白 → 抛 BaseException（消息含“推理依据”）；</li>
 *   <li>采纳成功：必须写结论修订快照、必须更新记录（version = 旧值+1、verdictSource=2）、
 *       必须更新申诉单、必须给用户发站内信、必须落操作日志；</li>
 *   <li>驳回：newVerdict / newReasoningChain 传不传都不影响，且不能误抛异常。</li>
 * </ul>
 *
 * <p>完全离线：mapper 全部 Mockito mock，不启动 Spring，不连 MySQL/Redis。
 * handleAccept 是 private，测试统一从 public 的 {@code handle(appealId, dto)} 进入。
 * 管理员身份通过 ThreadLocal 的 BaseContext 注入，并在 @AfterEach 清理，避免污染其他测试。</p>
 */
@ExtendWith(MockitoExtension.class)
class AdminAppealServiceImplHandleTest {

    private static final Long ADMIN_ID = 9001L;
    private static final Long APPEAL_ID = 555L;
    private static final Long RECORD_ID = 777L;
    private static final Long USER_ID = 66L;

    @Mock
    private AppealMapper appealMapper;
    @Mock
    private FactCheckRecordMapper factCheckRecordMapper;
    @Mock
    private ConclusionRevisionMapper conclusionRevisionMapper;
    @Mock
    private MessageMapper messageMapper;
    @Mock
    private OperationLogMapper operationLogMapper;

    @InjectMocks
    private AdminAppealServiceImpl adminAppealService;

    /** 模拟管理员登录：handle 内部通过 BaseContext.getCurrentId() 取管理员 ID */
    @BeforeEach
    void setUpAdminContext() {
        BaseContext.setCurrentId(ADMIN_ID);
        // 条件更新（第 4 批并发修复）的默认返回值。
        // 用 lenient：不少用例在校验阶段就抛异常、根本走不到条件更新，
        // 默认严格模式下会因"桩未被使用"而报 UnnecessaryStubbing。
        lenient().when(appealMapper.markHandledIfPending(
                anyLong(), anyInt(), anyString(), anyLong())).thenReturn(1);
        lenient().when(factCheckRecordMapper.updateConclusionIfStatus(
                anyLong(), anyInt(), any(), any(), any(), anyString(), any())).thenReturn(1);
    }

    /** 必须清理 ThreadLocal，否则会污染同线程上的其他测试 */
    @AfterEach
    void clearAdminContext() {
        BaseContext.removeCurrentId();
    }

    /** 待处理申诉（status=0 才允许处理） */
    private Appeal pendingAppeal() {
        return Appeal.builder()
                .id(APPEAL_ID)
                .recordId(RECORD_ID)
                .userId(USER_ID)
                .status(0)
                .build();
    }

    /** 已完成的检测记录（status=2 才允许被采纳改判），旧结论 = 2（假） */
    private FactCheckRecord finishedRecord(Integer version) {
        return FactCheckRecord.builder()
                .id(RECORD_ID)
                .userId(USER_ID)
                .status(2)
                .verdict(2)
                .verdictSource(1)
                .reasoningChain("旧的推理依据")
                .version(version)
                .build();
    }

    /** 默认路径：申诉待处理 + 关联记录已完成（旧版本号 4） */
    private void stubPendingAppealWithFinishedRecord() {
        when(appealMapper.getById(APPEAL_ID)).thenReturn(pendingAppeal());
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(finishedRecord(4));
    }

    private AdminAppealHandleDTO acceptDto(Integer newVerdict, String newReasoningChain) {
        AdminAppealHandleDTO dto = new AdminAppealHandleDTO();
        dto.setAccept(true);
        dto.setNewVerdict(newVerdict);
        dto.setNewReasoningChain(newReasoningChain);
        dto.setHandleResult("证据补充，同意改判");
        return dto;
    }

    // ------------------------------------------------------------------
    // 采纳分支：入参校验（前端漏字段缺陷的后端契约）
    // ------------------------------------------------------------------

    /**
     * newVerdict 缺失（null）时必须拒绝采纳，并且不得产生任何写库副作用。
     */
    @Test
    @DisplayName("采纳：newVerdict 为 null → 抛 BaseException（消息含“新结论”），且不写任何表")
    void shouldRejectAcceptWhenNewVerdictIsNull() {
        when(appealMapper.getById(APPEAL_ID)).thenReturn(pendingAppeal());

        BaseException ex = assertThrows(BaseException.class,
                () -> adminAppealService.handle(APPEAL_ID, acceptDto(null, "新的推理依据")));

        assertThat(ex.getMessage()).contains("新结论");
        verifyNoInteractions(conclusionRevisionMapper, factCheckRecordMapper, messageMapper, operationLogMapper);
        verify(appealMapper, never()).update(any(Appeal.class));
    }

    /**
     * newVerdict 是非法枚举值（4 不在 1真/2假/3无法判定 之内）时必须拒绝采纳。
     */
    @Test
    @DisplayName("采纳：newVerdict = 4（非法值）→ 抛 BaseException，且不写任何表")
    void shouldRejectAcceptWhenNewVerdictIsIllegalValue() {
        when(appealMapper.getById(APPEAL_ID)).thenReturn(pendingAppeal());

        BaseException ex = assertThrows(BaseException.class,
                () -> adminAppealService.handle(APPEAL_ID, acceptDto(4, "新的推理依据")));

        assertThat(ex.getMessage()).contains("新结论");
        verifyNoInteractions(conclusionRevisionMapper, factCheckRecordMapper, messageMapper, operationLogMapper);
    }

    /**
     * newReasoningChain 为 null 或空串时必须拒绝采纳。
     * 注意：此时 newVerdict 必须是合法值，否则会先命中“新结论”分支。
     *
     * <p>纯空白字符串（"   "）的覆盖见本文件末尾的
     * {@code shouldRejectAcceptWhenNewReasoningChainIsWhitespaceOnly}（第 6 批修复 P5-61）。</p>
     */
    @DisplayName("采纳：newReasoningChain 为 null 或空串 → 抛 BaseException（消息含“推理依据”）")
    @ParameterizedTest(name = "[{index}] newReasoningChain=[{0}]")
    @NullAndEmptySource
    void shouldRejectAcceptWhenNewReasoningChainIsBlank(String newReasoningChain) {
        when(appealMapper.getById(APPEAL_ID)).thenReturn(pendingAppeal());

        BaseException ex = assertThrows(BaseException.class,
                () -> adminAppealService.handle(APPEAL_ID, acceptDto(1, newReasoningChain)));

        assertThat(ex.getMessage()).contains("推理依据");
        verifyNoInteractions(conclusionRevisionMapper, factCheckRecordMapper, messageMapper, operationLogMapper);
    }

    // ------------------------------------------------------------------
    // 采纳分支：全部合法时的写入契约
    // ------------------------------------------------------------------

    /**
     * 采纳成功的完整写入契约：
     * 结论修订快照（source=2 表示来自申诉采纳）、记录更新（version=旧值+1、verdictSource=2）、
     * 申诉单置为已采纳（status=1）、给用户发站内信、落管理员操作日志。
     */
    @Test
    @DisplayName("采纳成功：写结论修订 + 更新记录(version=旧值+1, verdictSource=2) + 更新申诉单 + 发站内信 + 落操作日志")
    void shouldApplyAcceptWhenAllFieldsValid() {
        stubPendingAppealWithFinishedRecord();

        adminAppealService.handle(APPEAL_ID, acceptDto(3, "新的推理依据：已核实官方通报"));

        // 1) 结论修订快照
        ArgumentCaptor<ConclusionRevision> revisionCaptor = ArgumentCaptor.forClass(ConclusionRevision.class);
        verify(conclusionRevisionMapper).insert(revisionCaptor.capture());
        ConclusionRevision revision = revisionCaptor.getValue();
        assertThat(revision.getRecordId()).isEqualTo(RECORD_ID);
        assertThat(revision.getOldVerdict()).isEqualTo(2);
        assertThat(revision.getNewVerdict()).isEqualTo(3);
        assertThat(revision.getOldReasoningChain()).isEqualTo("旧的推理依据");
        assertThat(revision.getNewReasoningChain()).isEqualTo("新的推理依据：已核实官方通报");
        assertThat(revision.getReason()).isEqualTo("证据补充，同意改判");
        assertThat(revision.getSource()).isEqualTo(2);
        assertThat(revision.getAppealId()).isEqualTo(APPEAL_ID);
        assertThat(revision.getAdminId()).isEqualTo(ADMIN_ID);
        assertThat(revision.getCreateTime()).isNotNull();

        // 2) 记录最新结论（第 4 批改为条件更新 + SQL 侧原子 version+1）
        ArgumentCaptor<String> newChain = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> newVerdict = ArgumentCaptor.forClass(Integer.class);
        verify(factCheckRecordMapper).updateConclusionIfStatus(
                eq(RECORD_ID), eq(2), eq(null), newVerdict.capture(), eq(2), newChain.capture(), eq(null));
        assertThat(newVerdict.getValue()).isEqualTo(3);
        assertThat(newChain.getValue()).isEqualTo("新的推理依据：已核实官方通报");

        // 3) 申诉单：原子认领（仅当仍为待处理），status 置为 1
        ArgumentCaptor<String> handleResult = ArgumentCaptor.forClass(String.class);
        verify(appealMapper).markHandledIfPending(
                eq(APPEAL_ID), eq(1), handleResult.capture(), eq(ADMIN_ID));
        assertThat(handleResult.getValue()).isEqualTo("证据补充，同意改判");

        // 4) 站内信（type=4 申诉相关）
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper).insert(messageCaptor.capture());
        Message message = messageCaptor.getValue();
        assertThat(message.getUserId()).isEqualTo(USER_ID);
        assertThat(message.getType()).isEqualTo(4);
        assertThat(message.getTitle()).isEqualTo("申诉已采纳");
        assertThat(message.getRelatedId()).isEqualTo(APPEAL_ID);
        assertThat(message.getIsRead()).isEqualTo(0);

        // 5) 操作日志
        verify(operationLogMapper).insert(any(OperationLog.class));
    }

    /**
     * 记录 version 为 null 的兜底已不再需要由 Java 处理：
     * 版本号改由 SQL 侧 {@code version = version + 1} 原子自增，
     * 因此不存在"用 Java 读到的 null 去 +1 而 NPE"的路径。
     * 原来的 {@code shouldFallbackVersionToTwoWhenOldVersionIsNull} 已删除，
     * 由 {@link AdminRecordServiceImplConclusionTest} 与新的事务验证覆盖。
     */

    /**
     * 未填写 handleResult 时，采纳的默认说明文案必须落库（前端可不传备注）。
     */
    @Test
    @DisplayName("采纳成功：handleResult 缺失时使用默认文案“申诉采纳，修改结论”")
    void shouldUseDefaultHandleResultWhenAcceptingWithoutResult() {
        stubPendingAppealWithFinishedRecord();
        AdminAppealHandleDTO dto = acceptDto(2, "新的推理依据");
        dto.setHandleResult(null);

        adminAppealService.handle(APPEAL_ID, dto);

        ArgumentCaptor<ConclusionRevision> revisionCaptor = ArgumentCaptor.forClass(ConclusionRevision.class);
        verify(conclusionRevisionMapper).insert(revisionCaptor.capture());
        assertThat(revisionCaptor.getValue().getReason()).isEqualTo("申诉采纳，修改结论");

        ArgumentCaptor<String> handleResult = ArgumentCaptor.forClass(String.class);
        verify(appealMapper).markHandledIfPending(
                eq(APPEAL_ID), eq(1), handleResult.capture(), eq(ADMIN_ID));
        assertThat(handleResult.getValue()).isEqualTo("已采纳并修改结论");
    }

    // ------------------------------------------------------------------
    // 采纳分支：前置状态守卫
    // ------------------------------------------------------------------

    /**
     * 申诉不存在时必须抛 AppealNotFoundException，不能继续往下写库。
     */
    @Test
    @DisplayName("申诉不存在 → AppealNotFoundException，且无任何写库副作用")
    void shouldThrowWhenAppealNotFound() {
        when(appealMapper.getById(APPEAL_ID)).thenReturn(null);

        assertThrows(AppealNotFoundException.class,
                () -> adminAppealService.handle(APPEAL_ID, acceptDto(1, "新的推理依据")));

        verifyNoInteractions(conclusionRevisionMapper, factCheckRecordMapper, messageMapper, operationLogMapper);
    }

    /**
     * 申诉已被处理（status != 0）时必须拒绝重复操作。
     */
    @Test
    @DisplayName("申诉已处理（status=1）→ BaseException（提示勿重复操作）")
    void shouldRejectAlreadyHandledAppeal() {
        when(appealMapper.getById(APPEAL_ID)).thenReturn(Appeal.builder()
                .id(APPEAL_ID).recordId(RECORD_ID).userId(USER_ID).status(1).build());

        BaseException ex = assertThrows(BaseException.class,
                () -> adminAppealService.handle(APPEAL_ID, acceptDto(1, "新的推理依据")));

        assertThat(ex.getMessage()).contains("已处理");
        verifyNoInteractions(conclusionRevisionMapper, factCheckRecordMapper, messageMapper, operationLogMapper);
    }

    /**
     * 关联记录不存在时抛 RecordNotFoundException（属于 BaseException 子类）。
     */
    @Test
    @DisplayName("关联记录不存在 → RecordNotFoundException")
    void shouldThrowWhenRecordNotFound() {
        when(appealMapper.getById(APPEAL_ID)).thenReturn(pendingAppeal());
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(null);

        assertThrows(RecordNotFoundException.class,
                () -> adminAppealService.handle(APPEAL_ID, acceptDto(1, "新的推理依据")));

        verifyNoInteractions(conclusionRevisionMapper, messageMapper, operationLogMapper);
    }

    /**
     * 关联记录尚未完成（status != 2）时不允许采纳改判，避免改到一个还在检测中的结论。
     */
    @Test
    @DisplayName("关联记录未完成（status=0）→ BaseException（提示无法采纳修改）")
    void shouldRejectAcceptWhenRecordNotFinished() {
        when(appealMapper.getById(APPEAL_ID)).thenReturn(pendingAppeal());
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(FactCheckRecord.builder()
                .id(RECORD_ID).userId(USER_ID).status(0).version(1).build());

        BaseException ex = assertThrows(BaseException.class,
                () -> adminAppealService.handle(APPEAL_ID, acceptDto(1, "新的推理依据")));

        assertThat(ex.getMessage()).contains("未完成");
        verifyNoInteractions(conclusionRevisionMapper, messageMapper, operationLogMapper);
    }

    /**
     * 管理员未登录（BaseContext 为空）时必须抛“管理员未登录”，且不读任何表。
     */
    @Test
    @DisplayName("管理员未登录 → BaseException(ADMIN_NOT_LOGIN)")
    void shouldRejectWhenAdminNotLoggedIn() {
        BaseContext.removeCurrentId();

        BaseException ex = assertThrows(BaseException.class,
                () -> adminAppealService.handle(APPEAL_ID, acceptDto(1, "新的推理依据")));

        assertThat(ex.getMessage()).isEqualTo(MessageConstant.ADMIN_NOT_LOGIN);
        verifyNoInteractions(appealMapper, conclusionRevisionMapper, factCheckRecordMapper, messageMapper, operationLogMapper);
    }

    // ------------------------------------------------------------------
    // 驳回分支
    // ------------------------------------------------------------------

    /**
     * 驳回分支对 newVerdict / newReasoningChain 完全不敏感：
     * 前端多传、少传、传 null 都不应导致异常——这是“驳回永远可用”的保证。
     * 同时校验驳回的落库语义：申诉单 status=2、发送 type=4 站内信、落操作日志。
     */
    @Test
    @DisplayName("驳回：newVerdict/newReasoningChain 为 null 也不抛异常，且正常更新申诉单并通知用户")
    void shouldRejectAppealWithoutRequiringNewVerdictAndChain() {
        when(appealMapper.getById(APPEAL_ID)).thenReturn(pendingAppeal());
        AdminAppealHandleDTO dto = new AdminAppealHandleDTO();
        dto.setAccept(false);
        dto.setHandleResult("申诉理由不成立");
        dto.setNewVerdict(null);
        dto.setNewReasoningChain(null);

        adminAppealService.handle(APPEAL_ID, dto);

        ArgumentCaptor<String> handleResult = ArgumentCaptor.forClass(String.class);
        verify(appealMapper).markHandledIfPending(
                eq(APPEAL_ID), eq(2), handleResult.capture(), eq(ADMIN_ID));
        assertThat(handleResult.getValue()).isEqualTo("申诉理由不成立");

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getType()).isEqualTo(4);
        assertThat(messageCaptor.getValue().getTitle()).isEqualTo("申诉已驳回");

        verify(operationLogMapper).insert(any(OperationLog.class));
        // 驳回绝不修改检测记录、也绝不写结论修订
        verify(factCheckRecordMapper, never()).updateConclusionIfStatus(
                anyLong(), anyInt(), any(), any(), any(), anyString(), any());
        verify(conclusionRevisionMapper, never()).insert(any(ConclusionRevision.class));
    }

    /**
     * 驳回必须填写驳回原因；缺失（null / 空串）时抛 BaseException（消息含“驳回原因”）。
     */
    @Test
    @DisplayName("驳回：handleResult 缺失（null / 空串）→ BaseException（消息含“驳回原因”）")
    void shouldRejectRejectWithoutHandleResult() {
        when(appealMapper.getById(APPEAL_ID)).thenReturn(pendingAppeal());
        AdminAppealHandleDTO dto = new AdminAppealHandleDTO();
        dto.setAccept(false);
        dto.setHandleResult("");

        BaseException ex = assertThrows(BaseException.class, () -> adminAppealService.handle(APPEAL_ID, dto));

        assertThat(ex.getMessage()).contains("驳回原因");
        verify(appealMapper, never()).update(any(Appeal.class));
        verifyNoInteractions(messageMapper, operationLogMapper);
    }

    // ------------------------------------------------------------------
    // 空白字符串与首尾空白（第 6 批修复 P5-61）
    // ------------------------------------------------------------------

    /**
     * 纯空白字符串也必须被拒。
     *
     * <p>修复前用的是 Spring 的 {@code StringUtils.hasLength}（不 trim），
     * 因此 {@code "   "} 会被当成"有内容"放行，把空白推理依据写进结论修订快照，
     * 把空白驳回原因拼进站内信。现在入口先 trim、校验改用 {@code hasText}。</p>
     */
    @DisplayName("采纳：newReasoningChain 为纯空白 → 被拒且无任何写库副作用")
    @ParameterizedTest(name = "[{index}] newReasoningChain=[{0}]")
    @ValueSource(strings = {"   ", "\t", "\n  "})
    void shouldRejectAcceptWhenNewReasoningChainIsWhitespaceOnly(String newReasoningChain) {
        when(appealMapper.getById(APPEAL_ID)).thenReturn(pendingAppeal());

        BaseException ex = assertThrows(BaseException.class,
                () -> adminAppealService.handle(APPEAL_ID, acceptDto(1, newReasoningChain)));

        assertThat(ex.getMessage()).contains("推理依据");
        verifyNoInteractions(conclusionRevisionMapper, factCheckRecordMapper, messageMapper, operationLogMapper);
    }

    /**
     * 首尾空白应在落库前被去掉，避免把 {@code "  依据  "} 原样写进结论修订与检测记录。
     */
    @Test
    @DisplayName("采纳：推理依据首尾空白被 trim 后落库")
    void shouldTrimReasoningChainBeforePersisting() {
        stubPendingAppealWithFinishedRecord();

        adminAppealService.handle(APPEAL_ID, acceptDto(3, "   已核实官方通报   "));

        ArgumentCaptor<ConclusionRevision> captor = ArgumentCaptor.forClass(ConclusionRevision.class);
        verify(conclusionRevisionMapper).insert(captor.capture());
        assertThat(captor.getValue().getNewReasoningChain()).isEqualTo("已核实官方通报");
    }

    /**
     * 驳回原因同理：纯空白必须被拒，首尾空白应被 trim。
     */
    @DisplayName("驳回：handleResult 为纯空白 → 被拒且无写库副作用")
    @ParameterizedTest(name = "[{index}] handleResult=[{0}]")
    @ValueSource(strings = {"   ", "\t"})
    void shouldRejectRejectWhenHandleResultIsWhitespaceOnly(String handleResult) {
        when(appealMapper.getById(APPEAL_ID)).thenReturn(pendingAppeal());
        AdminAppealHandleDTO dto = new AdminAppealHandleDTO();
        dto.setAccept(false);
        dto.setHandleResult(handleResult);

        BaseException ex = assertThrows(BaseException.class, () -> adminAppealService.handle(APPEAL_ID, dto));

        assertThat(ex.getMessage()).contains("驳回原因");
        verify(appealMapper, never()).update(any(Appeal.class));
        verifyNoInteractions(messageMapper, operationLogMapper);
    }

    @Test
    @DisplayName("驳回：驳回原因首尾空白被 trim 后落库")
    void shouldTrimHandleResultBeforePersisting() {
        when(appealMapper.getById(APPEAL_ID)).thenReturn(pendingAppeal());
        AdminAppealHandleDTO dto = new AdminAppealHandleDTO();
        dto.setAccept(false);
        dto.setHandleResult("   申诉理由不成立   ");

        adminAppealService.handle(APPEAL_ID, dto);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(appealMapper).markHandledIfPending(eq(APPEAL_ID), eq(2), captor.capture(), eq(ADMIN_ID));
        assertThat(captor.getValue()).isEqualTo("申诉理由不成立");
    }

    // ------------------------------------------------------------------
    // 并发守卫（第 4 批）：条件更新返回 0 行 = 已被别人处理
    // ------------------------------------------------------------------

    /**
     * 这是本批修复的核心回归：两个管理员同时点"采纳"时，
     * 后到的那个必须**当场失败**，而不是把结论改第二遍、再写一条版本快照、再发一条站内信。
     *
     * <p>模拟方式：让 {@code updateConclusionIfStatus} 返回 0（表示"记录状态已变"）。
     * 修复前的实现没有条件更新，这个场景在代码里根本不存在对应分支。</p>
     */
    @Test
    @DisplayName("并发守卫：记录条件更新影响 0 行 → 抛业务异常，不再发站内信、不再写操作日志")
    void shouldFailWhenRecordConclusionUpdateAffectsNoRow() {
        stubPendingAppealWithFinishedRecord();
        when(factCheckRecordMapper.updateConclusionIfStatus(
                anyLong(), anyInt(), any(), any(), any(), anyString(), any())).thenReturn(0);

        BaseException ex = assertThrows(BaseException.class,
                () -> adminAppealService.handle(APPEAL_ID, acceptDto(3, "新的推理依据")));

        assertThat(ex.getMessage()).contains("记录状态已变化");
        verify(messageMapper, never()).insert(any(Message.class));
        verify(operationLogMapper, never()).insert(any(OperationLog.class));
    }

    /**
     * 申诉单被并发处理（{@code markHandledIfPending} 返回 0）→ 抛"已被处理"，
     * 且不再给用户发通知、不写操作日志。
     */
    @Test
    @DisplayName("并发守卫：申诉原子认领影响 0 行 → 抛“该申诉已被处理”，不发站内信")
    void shouldFailWhenAppealClaimAffectsNoRow() {
        when(appealMapper.getById(APPEAL_ID)).thenReturn(pendingAppeal());
        AdminAppealHandleDTO dto = new AdminAppealHandleDTO();
        dto.setAccept(false);
        dto.setHandleResult("申诉理由不成立");
        when(appealMapper.markHandledIfPending(anyLong(), anyInt(), anyString(), anyLong())).thenReturn(0);

        BaseException ex = assertThrows(BaseException.class, () -> adminAppealService.handle(APPEAL_ID, dto));

        assertThat(ex.getMessage()).contains("已被处理");
        verify(messageMapper, never()).insert(any(Message.class));
        verify(operationLogMapper, never()).insert(any(OperationLog.class));
    }
}
