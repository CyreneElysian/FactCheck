package com.factcheck.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.factcheck.entity.FactCheckRecord;
import com.factcheck.handler.DetectWebSocketHandler;
import com.factcheck.mapper.AuthorityInfoMapper;
import com.factcheck.mapper.ConclusionRevisionMapper;
import com.factcheck.mapper.DetectLogMapper;
import com.factcheck.mapper.EvidenceMapper;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.mapper.MessageMapper;
import com.factcheck.properties.AgentProperties;
import com.factcheck.service.AgentClient;
import com.factcheck.service.ContentSafetyService;
import com.factcheck.service.DetectService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link DetectServiceImpl#asyncDetect(Long, String)} 的"失败语义分流"单元测试。
 *
 * <p>背景缺陷：LLM 抖动（Agent 侧 error=true）曾被当成"AI 无法判断"，
 * 于是大量请求被灌进人工诊断队列。修复后的语义三分支必须被钉住：</p>
 * <ul>
 *   <li>error=true → 检测失败（可重试），绝不进人工队列；</li>
 *   <li>canJudge=true 且 verdict 可映射（TRUE/FALSE/UNVERIFIED）→ 已完成 + verdict + verdictSource=1；</li>
 *   <li>canJudge=false 且 error=false → 真正转人工。</li>
 * </ul>
 *
 * <h3>本测试在"事务拆分"（第 4 批）之后的断言口径变化</h3>
 * 原先 {@code asyncDetect} 自己既标 {@code @Transactional} 又直接写库，因此测试直接断言
 * {@code factCheckRecordMapper.update(...)} 与 {@code messageMapper.insert(...)}。
 * 现在写库已抽到独立 Bean {@link DetectResultWriter}（各自短事务），
 * 因此断言对象改为<b>"委托给哪个写回方法、传了什么参数"</b>——
 * 具体写库内容由 {@link DetectResultWriterTest} 负责验证。
 * 这样拆分后，本测试仍然锁住"失败语义分流"这条最重要的契约。
 *
 * <p>完全离线：所有依赖都是 Mockito mock，不启动 Spring、不连 MySQL/Redis、不访问 Python Agent。</p>
 */
@ExtendWith(MockitoExtension.class)
class DetectServiceImplAsyncDetectTest {

    private static final Long RECORD_ID = 100L;
    private static final Long USER_ID = 7L;
    private static final String CONTENT = "某地出现不明飞行物";

    @Mock
    private FactCheckRecordMapper factCheckRecordMapper;
    @Mock
    private AuthorityInfoMapper authorityInfoMapper;
    @Mock
    private ConclusionRevisionMapper conclusionRevisionMapper;
    @Mock
    private EvidenceMapper evidenceMapper;
    @Mock
    private DetectLogMapper detectLogMapper;
    @Mock
    private MessageMapper messageMapper;
    @Mock
    private AgentClient agentClient;
    @Mock
    private AgentProperties agentProperties;
    @Mock
    private ContentSafetyService contentSafetyService;
    @Mock
    private RedisTemplate redisTemplate;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private DetectWebSocketHandler detectWebSocketHandler;
    /** 生产代码里 @Lazy 自注入的自身代理，用于从 submit 里发起异步检测 */
    @Mock
    private DetectService detectService;
    /** 写回器：断言"委托给哪个短事务方法" */
    @Mock
    private DetectResultWriter resultWriter;

    @InjectMocks
    private DetectServiceImpl detectServiceImpl;

    /** 被检测的记录：必须是已存在且带 userId 的记录，否则 asyncDetect 会提前 return */
    private FactCheckRecord existingRecord() {
        return FactCheckRecord.builder()
                .id(RECORD_ID)
                .userId(USER_ID)
                .content(CONTENT)
                .status(0)
                .build();
    }

    private AgentClient.DetectResult agentResult(boolean error, boolean canJudge, String verdict) {
        AgentClient.DetectResult result = new AgentClient.DetectResult();
        result.setError(error);
        result.setCanJudge(canJudge);
        result.setVerdict(verdict);
        return result;
    }

    /**
     * Agent 侧故障（error=true）→ 检测失败（可重试），
     * 且不能写出任何结论、不能转人工（不写 verdictSource）。
     */
    @Test
    @DisplayName("Agent error=true → 走 writeFailed（检测失败），不写结论，不建语义索引")
    void shouldMarkStatusThreeWhenAgentReportsError() {
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(existingRecord());
        AgentClient.DetectResult result = agentResult(true, false, null);
        result.setHumanReason("LLM 调用超时");
        when(agentClient.detect(CONTENT)).thenReturn(result);

        detectServiceImpl.asyncDetect(RECORD_ID, CONTENT);

        // 失败原因用 Agent 给的 humanReason
        verify(resultWriter).writeFailed(eq(RECORD_ID), eq(USER_ID), eq("LLM 调用超时"));
        verify(resultWriter, never()).writeJudged(anyLong(), anyLong(), any(), any(), anyString(), any());
        verify(resultWriter, never()).writePendingHuman(anyLong(), anyLong(), anyString());
        verify(agentClient, never()).indexAdd(anyLong(), anyString());
    }

    /**
     * error=true 必须优先于 canJudge=true：即使 Agent 同时给出了 verdict，
     * 只要标记了故障，就不能按"已有结论"处理——这是防灌队列的核心优先级。
     */
    @Test
    @DisplayName("Agent error=true 优先于 canJudge=true → 仍然走 writeFailed，不写结论")
    void shouldTreatErrorAsFailureEvenWhenCanJudgeIsTrue() {
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(existingRecord());
        when(agentClient.detect(CONTENT)).thenReturn(agentResult(true, true, "TRUE"));

        detectServiceImpl.asyncDetect(RECORD_ID, CONTENT);

        // 未填 humanReason 时传 null，由写回器使用兜底文案
        verify(resultWriter).writeFailed(eq(RECORD_ID), eq(USER_ID), eq(null));
        verify(resultWriter, never()).writeJudged(anyLong(), anyLong(), any(), any(), anyString(), any());
        verify(agentClient, never()).indexAdd(anyLong(), anyString());
    }

    /**
     * 正常可判定：走 writeJudged，并且把 verdict / 置信度 / 推理链原样传下去，
     * 同时把内容加入语义去重索引。
     */
    @Test
    @DisplayName("canJudge=true 且 verdict=TRUE → 走 writeJudged(verdict=1)，并加入去重索引")
    void shouldMarkCompletedAndRecordVerdictWhenCanJudge() {
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(existingRecord());
        AgentClient.DetectResult result = agentResult(false, true, "TRUE");
        result.setConfidence(0.93D);
        result.setReasoningChain("依据官方通报，确认为真");
        when(agentClient.detect(CONTENT)).thenReturn(result);

        detectServiceImpl.asyncDetect(RECORD_ID, CONTENT);

        verify(resultWriter).writeJudged(eq(RECORD_ID), eq(USER_ID), eq(1), eq(0.93D),
                eq("依据官方通报，确认为真"), any(AgentClient.DetectResult.class));
        verify(resultWriter, never()).writeFailed(anyLong(), anyLong(), anyString());
        verify(resultWriter, never()).writePendingHuman(anyLong(), anyLong(), anyString());
        verify(agentClient).indexAdd(RECORD_ID, CONTENT);
    }

    /**
     * FALSE / UNVERIFIED 的映射同样锁定：2 与 3。
     */
    @Test
    @DisplayName("verdict=FALSE → 传 2；verdict=UNVERIFIED → 传 3")
    void shouldMapFalseAndUnverifiedVerdicts() {
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(existingRecord());
        when(agentClient.detect(CONTENT)).thenReturn(agentResult(false, true, "FALSE"));

        detectServiceImpl.asyncDetect(RECORD_ID, CONTENT);

        when(agentClient.detect(CONTENT)).thenReturn(agentResult(false, true, "UNVERIFIED"));
        detectServiceImpl.asyncDetect(RECORD_ID, CONTENT);

        ArgumentCaptor<Integer> verdicts = ArgumentCaptor.forClass(Integer.class);
        verify(resultWriter, org.mockito.Mockito.times(2)).writeJudged(
                eq(RECORD_ID), eq(USER_ID), verdicts.capture(), any(), any(), any());
        assertThat(verdicts.getAllValues()).containsExactly(2, 3);
    }

    /**
     * AI 真的无法判断（canJudge=false 且 error=false）→ 转人工诊断。
     * 这才是唯一允许进人工队列的分支。
     */
    @Test
    @DisplayName("canJudge=false 且 error=false → 走 writePendingHuman（转人工）")
    void shouldMarkPendingWhenAiCannotJudge() {
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(existingRecord());
        AgentClient.DetectResult result = agentResult(false, false, null);
        result.setHumanReason("公开信息不足，无法判断");
        when(agentClient.detect(CONTENT)).thenReturn(result);

        detectServiceImpl.asyncDetect(RECORD_ID, CONTENT);

        verify(resultWriter).writePendingHuman(eq(RECORD_ID), eq(USER_ID), eq("公开信息不足，无法判断"));
        verify(resultWriter, never()).writeFailed(anyLong(), anyLong(), anyString());
        verify(agentClient, never()).indexAdd(anyLong(), anyString());
    }

    /**
     * canJudge=true 但 verdict 字符串无法映射（脏数据）→ 不能当"已完成"处理。
     *
     * 归口到检测失败（可重试）而不是转人工：
     * 这属于 Agent 契约问题而不是"AI 确实判断不了"，人工队列应只放后者。
     */
    @Test
    @DisplayName("canJudge=true 但 verdict 无法映射 → 走 writeFailed，原因含 MAYBE，不进人工队列")
    void shouldMarkFailedWhenVerdictIsNotMappable() {
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(existingRecord());
        when(agentClient.detect(CONTENT)).thenReturn(agentResult(false, true, "MAYBE"));

        detectServiceImpl.asyncDetect(RECORD_ID, CONTENT);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(resultWriter).writeFailed(eq(RECORD_ID), eq(USER_ID), reason.capture());
        assertThat(reason.getValue()).contains("MAYBE");
        verify(resultWriter, never()).writeJudged(anyLong(), anyLong(), any(), any(), anyString(), any());
        verify(resultWriter, never()).writePendingHuman(anyLong(), anyLong(), anyString());
        // 脏结论不能进入语义去重索引
        verify(agentClient, never()).indexAdd(anyLong(), anyString());
    }

    /**
     * 记录不存在时直接返回，不能对不存在的记录做任何更新或通知，也不该调用 Agent。
     */
    @Test
    @DisplayName("记录不存在 → 提前返回，不调用 Agent、不写回、不发站内信")
    void shouldReturnEarlyWhenRecordNotFound() {
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(null);

        detectServiceImpl.asyncDetect(RECORD_ID, CONTENT);

        verify(agentClient, never()).detect(anyString());
        verify(resultWriter, never()).writeJudged(anyLong(), anyLong(), any(), any(), anyString(), any());
        verify(resultWriter, never()).writeFailed(anyLong(), anyLong(), anyString());
        verify(resultWriter, never()).writePendingHuman(anyLong(), anyLong(), anyString());
        verify(messageMapper, never()).insert(any());
    }

    /**
     * Agent 调用抛异常（HTTP 层故障，不是 error=true）→ 必须落到 writeFailed，
     * 保证记录不会永远停在 status=0（检测中）。
     */
    @Test
    @DisplayName("Agent 抛异常 → 走 writeFailed，记录不停留在 status=0")
    void shouldWriteFailedWhenAgentThrows() {
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(existingRecord());
        when(agentClient.detect(CONTENT)).thenThrow(new RuntimeException("connect timed out"));

        detectServiceImpl.asyncDetect(RECORD_ID, CONTENT);

        verify(resultWriter).writeFailed(eq(RECORD_ID), eq(USER_ID), eq("检测服务异常，请稍后重试"));
        verify(resultWriter, never()).writeJudged(anyLong(), anyLong(), any(), any(), anyString(), any());
    }

    /**
     * 最坏情况：连"写回失败结果"都抛异常（例如数据库已经不可用）→
     * 必须再兜底一次 markFailedQuietly，而不是把异常吞掉让记录卡在检测中。
     */
    @Test
    @DisplayName("writeFailed 自身失败 → 继续兜底调用 markFailedQuietly")
    void shouldFallBackToQuietMarkWhenWriteFailedThrows() {
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(existingRecord());
        when(agentClient.detect(CONTENT)).thenThrow(new RuntimeException("connect timed out"));
        when(resultWriter.writeFailed(anyLong(), anyLong(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        detectServiceImpl.asyncDetect(RECORD_ID, CONTENT);

        verify(resultWriter).markFailedQuietly(RECORD_ID);
    }
}
