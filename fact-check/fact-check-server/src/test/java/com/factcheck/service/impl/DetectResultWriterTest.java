package com.factcheck.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import com.factcheck.entity.Evidence;
import com.factcheck.entity.FactCheckRecord;
import com.factcheck.entity.Message;
import com.factcheck.mapper.EvidenceMapper;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.mapper.MessageMapper;
import com.factcheck.service.AgentClient;
import com.factcheck.service.HotRankService;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
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
 * {@link DetectResultWriter} 的写库契约测试。
 *
 * <p>这三条路径对应"AI 能判定 / 检测失败 / 转人工"三种结果，
 * 每条都必须做到：<b>更新记录状态 + 发对应类型的站内信</b>（成功路径还要落证据）。
 * 状态与站内信类型是前端与用户可见的最终结果，写错就会让用户看到错误结论或收不到通知。</p>
 *
 * <p>完全离线：不启动 Spring、不连 MySQL/Redis。
 * 注意 {@code @Transactional} 在纯单测里不会生效（没有代理），
 * 因此本测试验证的是**方法内做了哪些写操作**，而不是"事务是否回滚"——
 * 回滚语义由端到端验证覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DetectResultWriterTest {

    private static final Long RECORD_ID = 100L;
    private static final Long USER_ID = 7L;

    @Mock
    private FactCheckRecordMapper factCheckRecordMapper;
    @Mock
    private EvidenceMapper evidenceMapper;
    @Mock
    private MessageMapper messageMapper;
    @Mock
    private HotRankService hotRankService;

    @InjectMocks
    private DetectResultWriter writer;

    private FactCheckRecord captureRecord() {
        ArgumentCaptor<FactCheckRecord> captor = ArgumentCaptor.forClass(FactCheckRecord.class);
        verify(factCheckRecordMapper).update(captor.capture());
        return captor.getValue();
    }

    private Message captureMessage() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper).insert(captor.capture());
        return captor.getValue();
    }

    private AgentClient.DetectResult resultWithEvidence(int count) {
        AgentClient.DetectResult result = new AgentClient.DetectResult();
        if (count == 0) {
            result.setEvidence(Collections.emptyList());
            return result;
        }
        java.util.List<AgentClient.DetectEvidence> list = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            AgentClient.DetectEvidence e = new AgentClient.DetectEvidence();
            e.setTitle("证据" + i);
            e.setUrl("https://example.com/" + i);
            e.setSnippet("摘要" + i);
            e.setSource("example.com");
            e.setSourceCredibility(new BigDecimal("0.95"));
            e.setRelevanceScore(new BigDecimal("0.80"));
            list.add(e);
        }
        result.setEvidence(list);
        return result;
    }

    @Test
    @DisplayName("writeJudged：status=2 + verdict + verdictSource=1 + 置信度 + 完成时间，站内信 type=1")
    void writeJudgedShouldPersistConclusion() {
        boolean ok = writer.writeJudged(RECORD_ID, USER_ID, 2, 0.93D, "依据官方通报", resultWithEvidence(0));

        assertThat(ok).isTrue();
        FactCheckRecord updated = captureRecord();
        assertThat(updated.getId()).isEqualTo(RECORD_ID);
        assertThat(updated.getStatus()).isEqualTo(2);
        assertThat(updated.getVerdict()).isEqualTo(2);
        assertThat(updated.getVerdictSource()).isEqualTo(1);
        assertThat(updated.getConfidence()).isEqualByComparingTo(new BigDecimal("0.93"));
        assertThat(updated.getReasoningChain()).isEqualTo("依据官方通报");
        assertThat(updated.getCompleteTime()).isNotNull();

        Message msg = captureMessage();
        assertThat(msg.getUserId()).isEqualTo(USER_ID);
        assertThat(msg.getType()).isEqualTo(1);
        assertThat(msg.getRelatedId()).isEqualTo(RECORD_ID);
        assertThat(msg.getIsRead()).isEqualTo(0);
    }

    @Test
    @DisplayName("writeJudged：置信度为 null 时不写 confidence（不能落 0，0 是有效置信度）")
    void writeJudgedShouldKeepNullConfidence() {
        writer.writeJudged(RECORD_ID, USER_ID, 1, null, "无置信度", resultWithEvidence(0));

        assertThat(captureRecord().getConfidence()).isNull();
    }

    @Test
    @DisplayName("writeJudged：有证据时先按记录清空再批量写入，且 sourceType=1（AI 检索）")
    @SuppressWarnings("unchecked")
    void writeJudgedShouldReplaceEvidence() {
        writer.writeJudged(RECORD_ID, USER_ID, 1, 0.9D, "chain", resultWithEvidence(3));

        verify(evidenceMapper).deleteByRecordId(RECORD_ID);
        ArgumentCaptor<List<Evidence>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(evidenceMapper).insertBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(3);
        assertThat(captor.getValue().get(0).getRecordId()).isEqualTo(RECORD_ID);
        assertThat(captor.getValue().get(0).getSourceType()).isEqualTo(1);
        assertThat(captor.getValue().get(0).getTitle()).isEqualTo("证据0");
    }

    @Test
    @DisplayName("writeJudged：无证据时不碰证据表（不能把已有证据误删）")
    void writeJudgedShouldNotTouchEvidenceWhenEmpty() {
        writer.writeJudged(RECORD_ID, USER_ID, 1, 0.9D, "chain", resultWithEvidence(0));

        verify(evidenceMapper, never()).deleteByRecordId(anyLong());
        verify(evidenceMapper, never()).insertBatch(any());
    }

    @Test
    @DisplayName("writeFailed：status=3 + aiSummary=失败原因，站内信 type=6")
    void writeFailedShouldPersistFailure() {
        writer.writeFailed(RECORD_ID, USER_ID, "LLM 调用超时");

        FactCheckRecord updated = captureRecord();
        assertThat(updated.getStatus()).isEqualTo(3);
        assertThat(updated.getAiSummary()).isEqualTo("LLM 调用超时");
        assertThat(updated.getVerdict()).isNull();

        Message msg = captureMessage();
        assertThat(msg.getType()).isEqualTo(6);
        assertThat(msg.getUserId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("writeFailed：原因为 null 时用兜底文案，不能写入 null 让前端显示空白")
    void writeFailedShouldUseFallbackReason() {
        writer.writeFailed(RECORD_ID, USER_ID, null);

        assertThat(captureRecord().getAiSummary()).isEqualTo("检测服务异常，请稍后重试");
    }

    @Test
    @DisplayName("writePendingHuman：status=1 + aiSummary，站内信 type=2（进入人工队列）")
    void writePendingHumanShouldPersistPending() {
        writer.writePendingHuman(RECORD_ID, USER_ID, "公开信息不足，无法判断");

        FactCheckRecord updated = captureRecord();
        assertThat(updated.getStatus()).isEqualTo(1);
        assertThat(updated.getAiSummary()).isEqualTo("公开信息不足，无法判断");
        assertThat(updated.getVerdict()).isNull();
        assertThat(updated.getVerdictSource()).isNull();

        Message msg = captureMessage();
        assertThat(msg.getType()).isEqualTo(2);
    }

    @Test
    @DisplayName("writePendingHuman：原因为 null 时用兜底文案")
    void writePendingHumanShouldUseFallbackReason() {
        writer.writePendingHuman(RECORD_ID, USER_ID, null);

        assertThat(captureRecord().getAiSummary()).isEqualTo("AI 无法判断，转人工诊断");
    }

    @Test
    @DisplayName("markFailedQuietly：只改状态为 3，不发站内信")
    void markFailedQuietlyShouldOnlyUpdateStatus() {
        writer.markFailedQuietly(RECORD_ID);

        assertThat(captureRecord().getStatus()).isEqualTo(3);
        verify(messageMapper, never()).insert(any());
    }

    @Test
    @DisplayName("markFailedQuietly：自身异常必须被吞掉（兜底路径不能再抛，否则会掩盖原始异常）")
    void markFailedQuietlyShouldSwallowException() {
        // update 返回 void，必须用 doThrow().when() 而不是 when().thenThrow()
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(factCheckRecordMapper).update(any(FactCheckRecord.class));

        // 不抛异常即通过
        writer.markFailedQuietly(RECORD_ID);

        verify(factCheckRecordMapper).update(any(FactCheckRecord.class));
    }
}
