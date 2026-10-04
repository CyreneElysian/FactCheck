package com.factcheck.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.factcheck.entity.FactCheckRecord;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.service.AgentClient;
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
 * 分类（前端"消息分类"）必须传到 Agent 的契约测试。
 *
 * <h3>为什么单独测这一条</h3>
 * 分类此前**只用于展示与筛选**：Java 侧取到 category 后只落库、只打日志，
 * 调 Agent 时 `body.put("text", text)` 就把它丢了（见 AgentClient.detect 的历史实现）。
 * 于是"按分类使用不同提示词"这个能力在链路上是断的。
 *
 * <p>这类"字段存了但没传出去"的缺陷不会报错、不会告警，只能在端到端行为上体现为
 * "提示词好像没生效"——所以必须有一条测试**直接断言分类到达了 Agent 调用**，
 * 而不是只断言"检测成功"。</p>
 *
 * <p>另一处刻意的设计：分类取自 `record.getCategory()` 而非方法入参，
 * 这样重试路径（handleExactHit / 管理端 redetect）也自然带上分类。
 * 下面的 {@code categoryFromRecordIsUsed} 就钉住这一点。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DetectCategoryForwardTest {

    private static final Long RECORD_ID = 27L;
    private static final Long USER_ID = 1L;
    private static final String CONTENT = "世界卫生组织已将熬夜列为2A类致癌物，所以长期熬夜一定会得癌症。";

    @Mock
    private FactCheckRecordMapper factCheckRecordMapper;
    @Mock
    private AgentClient agentClient;
    @Mock
    private DetectResultWriter resultWriter;

    @InjectMocks
    private DetectServiceImpl detectServiceImpl;

    private FactCheckRecord recordWithCategory(String category) {
        FactCheckRecord record = new FactCheckRecord();
        record.setId(RECORD_ID);
        record.setUserId(USER_ID);
        record.setContent(CONTENT);
        record.setStatus(0);
        record.setCategory(category);
        return record;
    }

    private AgentClient.DetectResult okResult() {
        AgentClient.DetectResult result = new AgentClient.DetectResult();
        result.setError(false);
        result.setCanJudge(false);
        result.setHumanReason("转人工");
        return result;
    }

    private String captureCategory() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(agentClient).detect(eq(CONTENT), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("记录里的分类被传给 Agent（这就是「存了但没传出去」的回归测试）")
    void categoryFromRecordIsUsed() {
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(recordWithCategory("健康"));
        when(agentClient.detect(anyString(), any())).thenReturn(okResult());

        detectServiceImpl.asyncDetect(RECORD_ID, CONTENT);

        assertThat(captureCategory()).isEqualTo("健康");
    }

    @Test
    @DisplayName("分类为 null 时照样调用（传 null，不能因此不检测）")
    void nullCategoryStillDetects() {
        when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(recordWithCategory(null));
        when(agentClient.detect(anyString(), any())).thenReturn(okResult());

        detectServiceImpl.asyncDetect(RECORD_ID, CONTENT);

        assertThat(captureCategory()).isNull();
        // 关键：分类缺失不能导致"整条记录被跳过"
        verify(resultWriter).writePendingHuman(eq(RECORD_ID), eq(USER_ID), anyString());
    }

    @Test
    @DisplayName("六种分类原样透传，Java 侧不做白名单（未知分类由 Agent 退回通用提示词）")
    void allCategoriesPassThrough() {
        for (String category : new String[]{"其他", "健康", "社会", "财经", "科技", "政策", "未知分类"}) {
            org.mockito.Mockito.reset(factCheckRecordMapper, agentClient, resultWriter);
            when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(recordWithCategory(category));
            when(agentClient.detect(anyString(), any())).thenReturn(okResult());

            detectServiceImpl.asyncDetect(RECORD_ID, CONTENT);

            assertThat(captureCategory())
                    .as("分类 %s 应原样透传", category)
                    .isEqualTo(category);
        }
    }

    @Test
    @DisplayName("分类不参与状态流转：无论有无分类，未判定都走 writePendingHuman")
    void categoryDoesNotAffectStateFlow() {
        for (String category : new String[]{"健康", null}) {
            org.mockito.Mockito.reset(factCheckRecordMapper, agentClient, resultWriter);
            when(factCheckRecordMapper.getById(RECORD_ID)).thenReturn(recordWithCategory(category));
            when(agentClient.detect(anyString(), any())).thenReturn(okResult());

            detectServiceImpl.asyncDetect(RECORD_ID, CONTENT);

            verify(resultWriter).writePendingHuman(anyLong(), anyLong(), anyString());
        }
    }
}
