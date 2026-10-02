package com.factcheck.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.factcheck.entity.FactCheckRecord;
import com.factcheck.entity.Message;
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
 * {@link DetectServiceImpl#asyncDetect(Long, String)} 的“失败语义分流”单元测试。
 *
 * <p>背景缺陷：LLM 抖动（Agent 侧 error=true）曾被当成“AI 无法判断”，
 * 于是大量请求被灌进人工诊断队列。修复后的语义三分支必须被钉住：</p>
 * <ul>
 *   <li>error=true → status=3（检测失败，可重试），绝不进人工队列；</li>
 *   <li>canJudge=true 且 verdict 可映射（TRUE/FALSE/UNVERIFIED）→ status=2 + verdict + verdictSource=1；</li>
 *   <li>canJudge=false 且 error=false → status=1（真正转人工）。</li>
 * </ul>
 *
 * <p>完全离线：所有依赖（AgentClient / 各 Mapper / Redis 模板 / WebSocket handler / 自身代理）
 * 都是 Mockito mock，不启动 Spring、不连 MySQL/Redis、不访问 Python Agent。
 * @Async 与 @Transactional 在纯单测里是惰性注解，不影响方法调用。</p>
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

    /** 捕获 factCheckRecordMapper.update(...) 的唯一一次调用参数 */
    private FactCheckRecord captureUpdatedRecord() {
        ArgumentCaptor<FactCheckRecord> captor = ArgumentCaptor.forClass(FactCheckRecord.class);
        verify(factCheckRecordMapper).update(captor.capture());
        return captor.getValue();
    }

    private Message captureInsertedMessage() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper).insert(captor.capture());
        return captor.getValue();
    }

    /**
     * Agent 侧故障（error=true）→ status=3（检测失败，用户可重试），
     * 且不能写出任何结论、不能转人工（不写 verdictSource）。
     */
    @Test
    @DisplayName("Agent error=true → status=3（检测失败），不写 verdict/verdictSource，不建语义索引")
    void shouldMarkStatusThreeWhenAgentReportsError() {
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(existingRecord());
        AgentClient.DetectResult result = agentResult(true, false, null);
        result.setHumanReason("LLM 调用超时");
        when(agentClient.detect(CONTENT)).thenReturn(result);

        detectServiceImpl.asyncDetect(RECORD_ID, CONTENT);

        FactCheckRecord updated = captureUpdatedRecord();
        assertThat(updated.getId()).isEqualTo(RECORD_ID);
        assertThat(updated.getStatus()).isEqualTo(3);
        assertThat(updated.getVerdict()).isNull();
        assertThat(updated.getVerdictSource()).isNull();
        assertThat(updated.getAiSummary()).isEqualTo("LLM 调用超时");

        // 失败信 type=6，而不是“进入人工队列”的 type=2
        assertThat(captureInsertedMessage().getType()).isEqualTo(6);
        verify(agentClient, never()).indexAdd(anyLong(), anyString());
    }

    /**
     * error=true 必须优先于 canJudge=true：即使 Agent 同时给出了 verdict，
     * 只要标记了故障，就不能按“已有结论”处理——这是防灌队列的核心优先级。
     */
    @Test
    @DisplayName("Agent error=true 优先于 canJudge=true → 仍然 status=3，不写结论")
    void shouldTreatErrorAsFailureEvenWhenCanJudgeIsTrue() {
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(existingRecord());
        when(agentClient.detect(CONTENT)).thenReturn(agentResult(true, true, "TRUE"));

        detectServiceImpl.asyncDetect(RECORD_ID, CONTENT);

        FactCheckRecord updated = captureUpdatedRecord();
        assertThat(updated.getStatus()).isEqualTo(3);
        assertThat(updated.getVerdict()).isNull();
        assertThat(updated.getVerdictSource()).isNull();
        // 未填 humanReason 时使用兜底文案
        assertThat(updated.getAiSummary()).isEqualTo("检测服务异常，请稍后重试");
    }

    /**
     * 正常可判定：status=2（已完成）、verdict 映射为 1、verdictSource=1（AI 判定）、
     * 置信度与推理链落库、发“检测完成”站内信（type=1）、并把内容加入语义去重索引。
     */
    @Test
    @DisplayName("canJudge=true 且 verdict=TRUE → status=2, verdict=1, verdictSource=1，并加入去重索引")
    void shouldMarkCompletedAndRecordVerdictWhenCanJudge() {
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(existingRecord());
        AgentClient.DetectResult result = agentResult(false, true, "TRUE");
        result.setConfidence(0.93D);
        result.setReasoningChain("依据官方通报，确认为真");
        when(agentClient.detect(CONTENT)).thenReturn(result);

        detectServiceImpl.asyncDetect(RECORD_ID, CONTENT);

        FactCheckRecord updated = captureUpdatedRecord();
        assertThat(updated.getStatus()).isEqualTo(2);
        assertThat(updated.getVerdict()).isEqualTo(1);
        assertThat(updated.getVerdictSource()).isEqualTo(1);
        assertThat(updated.getReasoningChain()).isEqualTo("依据官方通报，确认为真");
        assertThat(updated.getConfidence()).isNotNull();
        assertThat(updated.getConfidence().doubleValue()).isEqualTo(0.93D);
        assertThat(updated.getCompleteTime()).isNotNull();

        assertThat(captureInsertedMessage().getType()).isEqualTo(1);
        verify(agentClient).indexAdd(RECORD_ID, CONTENT);
    }

    /**
     * FALSE / UNVERIFIED 的映射同样锁定：2 与 3。
     */
    @Test
    @DisplayName("verdict=FALSE → verdict=2；verdict=UNVERIFIED → verdict=3")
    void shouldMapFalseAndUnverifiedVerdicts() {
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(existingRecord());
        when(agentClient.detect(CONTENT)).thenReturn(agentResult(false, true, "FALSE"));

        detectServiceImpl.asyncDetect(RECORD_ID, CONTENT);
        assertThat(captureUpdatedRecord().getVerdict()).isEqualTo(2);

        // 第二次调用：UNVERIFIED
        when(agentClient.detect(CONTENT)).thenReturn(agentResult(false, true, "UNVERIFIED"));
        detectServiceImpl.asyncDetect(RECORD_ID, CONTENT);
        ArgumentCaptor<FactCheckRecord> captor = ArgumentCaptor.forClass(FactCheckRecord.class);
        verify(factCheckRecordMapper, times(2)).update(captor.capture());
        assertThat(captor.getAllValues().get(1).getVerdict()).isEqualTo(3);
        assertThat(captor.getAllValues().get(1).getStatus()).isEqualTo(2);
    }

    /**
     * AI 真的无法判断（canJudge=false 且 error=false）→ status=1（转人工诊断），
     * 并发送“已进入人工诊断队列”站内信（type=2）。这才是唯一允许进人工队列的分支。
     */
    @Test
    @DisplayName("canJudge=false 且 error=false → status=1（转人工），站内信 type=2")
    void shouldMarkPendingWhenAiCannotJudge() {
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(existingRecord());
        AgentClient.DetectResult result = agentResult(false, false, null);
        result.setHumanReason("公开信息不足，无法判断");
        when(agentClient.detect(CONTENT)).thenReturn(result);

        detectServiceImpl.asyncDetect(RECORD_ID, CONTENT);

        FactCheckRecord updated = captureUpdatedRecord();
        assertThat(updated.getStatus()).isEqualTo(1);
        assertThat(updated.getVerdict()).isNull();
        assertThat(updated.getVerdictSource()).isNull();
        assertThat(updated.getAiSummary()).isEqualTo("公开信息不足，无法判断");
        assertThat(captureInsertedMessage().getType()).isEqualTo(2);
        verify(agentClient, never()).indexAdd(anyLong(), anyString());
    }

    /**
     * canJudge=true 但 verdict 字符串无法映射（脏数据）→ 不能当"已完成"处理。
     *
     * 归口到 status=3（检测失败，可重试）而不是 status=1（转人工）：
     * 这属于 Agent 契约问题而不是"AI 确实判断不了"，人工队列应只放后者。
     */
    @Test
    @DisplayName("canJudge=true 但 verdict 无法映射 → status=3（检测失败），不写结论、不进人工队列")
    void shouldMarkFailedWhenVerdictIsNotMappable() {
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(existingRecord());
        when(agentClient.detect(CONTENT)).thenReturn(agentResult(false, true, "MAYBE"));

        detectServiceImpl.asyncDetect(RECORD_ID, CONTENT);

        FactCheckRecord updated = captureUpdatedRecord();
        assertThat(updated.getStatus()).isEqualTo(3);
        assertThat(updated.getVerdict()).isNull();
        assertThat(updated.getVerdictSource()).isNull();
        assertThat(updated.getAiSummary()).contains("MAYBE");

        // 走"检测失败"通知（type=6），而不是"已进入人工诊断队列"（type=2）
        assertThat(captureInsertedMessage().getType()).isEqualTo(6);
        // 脏结论不能进入语义去重索引
        verify(agentClient, never()).indexAdd(anyLong(), anyString());
    }

    /**
     * 记录不存在时直接返回，不能对不存在的记录做任何更新或通知。
     */
    @Test
    @DisplayName("记录不存在 → 提前返回，不调用 Agent、不更新记录、不发站内信")
    void shouldReturnEarlyWhenRecordNotFound() {
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(null);

        detectServiceImpl.asyncDetect(RECORD_ID, CONTENT);

        verify(factCheckRecordMapper, never()).update(any(FactCheckRecord.class));
        verify(agentClient, never()).detect(anyString());
        verify(messageMapper, never()).insert(any(Message.class));
    }
}
