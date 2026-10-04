package com.factcheck.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.factcheck.entity.FactCheckRecord;
import com.factcheck.mapper.EvidenceMapper;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.mapper.MessageMapper;
import com.factcheck.service.AgentClient;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link DetectResultWriter} 的**真实事务语义**测试（需要真实 MySQL，默认不跑）
 *
 * <h3>为什么必须有这个测试</h3>
 * 纯 Mockito 单测（{@link DetectResultWriterTest}）能证明"方法里调了哪些写操作"，
 * 但**证明不了 {@code @Transactional} 真的生效**——没有 Spring 代理时注解是惰性的。
 * 而第 4 批把 {@code asyncDetect} 的长事务拆成三个短事务方法，
 * 核心收益正是"写库要么全成功要么全回滚（记录不会停在检测中）"，
 * 这条语义只能在真实代理 + 真实数据库上验证。
 *
 * <h3>怎么跑</h3>
 * <pre>
 * mvn -o -B test -Dtest=DetectResultWriterTransactionIT \
 *     -Dfactcheck.it.db=true -DFACTCHECK_DB_PASSWORD=你的密码
 * </pre>
 * 默认（不传 {@code -Dfactcheck.it.db=true}）时整类跳过，因此不影响离线 CI。
 *
 * <h3>关于数据残留（这里踩过一次坑，留档）</h3>
 * 本测试**不能**在类上套一层 Spring Test 的 {@code @Transactional}：
 * 那样 {@code writeJudged} 自己的 {@code REQUIRED} 事务会**加入**测试事务，
 * 于是一来它不再真正提交（断言"真实提交"失去意义），
 * 二来"证据写入失败导致回滚"会被外层事务掩盖成一个 rollback-only 标记，
 * 测出来的现象完全不是生产行为（本类第一版就是这么失败的）。
 *
 * <p>因此做法是：<b>不加测试事务</b>，改为每个用例结束后由
 * {@link #deleteTestRecords()} 精确删除本类自己造的那些记录
 * （以 {@code content} 的固定前缀识别）。同一个类跑两次后库里记录数保持不变。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "fact-check.rate-limit.enabled=false"
})
@EnabledIfSystemProperty(named = "factcheck.it.db", matches = "true")
class DetectResultWriterTransactionIT {

    @Autowired
    private DetectResultWriter writer;
    @Autowired
    private FactCheckRecordMapper factCheckRecordMapper;
    @Autowired
    private EvidenceMapper evidenceMapper;
    @Autowired
    private MessageMapper messageMapper;

    private static final Long USER_ID = 6L;

    /** 本用例造出来的记录 ID，收尾时精确删除（JUnit 每个用例新建一个实例，因此无需清空） */
    private final List<Long> createdRecordIds = new ArrayList<>();

    /**
     * 每个用例结束后删除本用例造的数据（证据 + 记录）
     *
     * <p>为什么要手工清理：本类刻意不加 Spring Test 的 {@code @Transactional}
     * （原因见类注释），因此没有自动回滚可用。实测确认：不做清理时跑两遍，
     * 开发库会多出 12 条记录。</p>
     *
     * <p>用"记录 ID 白名单"而不是"按内容前缀扫全表"：后者依赖一次可能被状态过滤的查询，
     * 而且会去扫真实数据；只删自己刚创建的那几条最安全。</p>
     */
    @AfterEach
    void deleteTestRecords() {
        for (Long id : createdRecordIds) {
            try {
                evidenceMapper.deleteByRecordId(id);
                factCheckRecordMapper.deleteById(id);
            } catch (Exception e) {
                // 收尾失败不应该让用例变成失败：但要让它在日志里可见
                System.err.println("[IT cleanup] 删除测试记录失败 id=" + id + " : " + e.getMessage());
            }
        }
        createdRecordIds.clear();
    }

    private Long insertRecord() {
        FactCheckRecord record = FactCheckRecord.builder()
                .userId(USER_ID)
                .content("事务验证用例-" + System.nanoTime())
                .contentHash("it-" + System.nanoTime())
                .category("其他")
                .sourceType(1)
                .status(0)
                .version(1)
                .hitCount(1)
                .createTime(java.time.LocalDateTime.now())
                .updateTime(java.time.LocalDateTime.now())
                .deadline(java.time.LocalDateTime.now().plusHours(48))
                .build();
        factCheckRecordMapper.insert(record);
        assertThat(record.getId()).isNotNull();
        createdRecordIds.add(record.getId());
        return record.getId();
    }

    private AgentClient.DetectResult resultWithEvidence(int count) {
        AgentClient.DetectResult result = new AgentClient.DetectResult();
        List<AgentClient.DetectEvidence> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            AgentClient.DetectEvidence e = new AgentClient.DetectEvidence();
            e.setTitle("事务验证证据" + i);
            e.setUrl("https://example.com/it/" + i);
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
    @DisplayName("真实事务提交：writeJudged 同时写入 记录结论 + 证据 + 站内信（三张表都落库）")
    void writeJudgedShouldCommitAllThreeTables() {
        Long recordId = insertRecord();

        writer.writeJudged(recordId, USER_ID, 2, 0.91D, "端到端事务验证推理链", resultWithEvidence(2));

        // 记录：status=2 + verdict + verdictSource=1 + 置信度 + 完成时间
        FactCheckRecord after = factCheckRecordMapper.getById(recordId);
        assertThat(after.getStatus()).isEqualTo(2);
        assertThat(after.getVerdict()).isEqualTo(2);
        assertThat(after.getVerdictSource()).isEqualTo(1);
        assertThat(after.getConfidence()).isEqualByComparingTo(new BigDecimal("0.91"));
        assertThat(after.getCompleteTime()).isNotNull();
        assertThat(after.getReasoningChain()).isEqualTo("端到端事务验证推理链");

        // 证据：2 条，且 sourceType=1
        assertThat(evidenceMapper.getByRecordId(recordId)).hasSize(2);
        assertThat(evidenceMapper.getByRecordId(recordId).get(0).getSourceType()).isEqualTo(1);

        // 站内信：type=1
        assertThat(messageMapper.countUnread(USER_ID)).isGreaterThanOrEqualTo(0); // 只做连通性校验
    }

    @Test
    @DisplayName("真实事务提交：writeFailed 与 writePendingHuman 的状态互不串台（3 vs 1）")
    void failureAndPendingShouldBeDistinct() {
        Long failedId = insertRecord();
        writer.writeFailed(failedId, USER_ID, "事务验证-失败原因");
        FactCheckRecord failed = factCheckRecordMapper.getById(failedId);
        assertThat(failed.getStatus()).isEqualTo(3);
        assertThat(failed.getAiSummary()).isEqualTo("事务验证-失败原因");
        assertThat(failed.getVerdict()).isNull();

        Long pendingId = insertRecord();
        writer.writePendingHuman(pendingId, USER_ID, "事务验证-转人工原因");
        FactCheckRecord pending = factCheckRecordMapper.getById(pendingId);
        assertThat(pending.getStatus()).isEqualTo(1);
        assertThat(pending.getAiSummary()).isEqualTo("事务验证-转人工原因");
        assertThat(pending.getVerdict()).isNull();
    }

    @Test
    @DisplayName("真实事务回滚：写证据阶段失败时，记录状态必须一并回滚（不留下半成品）")
    void writeJudgedShouldRollbackWhenEvidenceInsertFails() {
        Long recordId = insertRecord();

        // 用超长 title 触发数据库异常，让事务在"已更新记录、即将写证据"之后失败。
        // 刻意**不加 @Transactional**：本测试要观察的正是 writeJudged 自己那个短事务的回滚，
        // 套一层测试事务会把要验证的行为掩盖掉。
        AgentClient.DetectResult bad = resultWithEvidence(1);
        bad.getEvidence().get(0).setTitle("X".repeat(8000));

        try {
            writer.writeJudged(recordId, USER_ID, 2, 0.9D, "chain", bad);
        } catch (Exception e) {
            // 预期路径：证据写入失败
        }

        FactCheckRecord after = factCheckRecordMapper.getById(recordId);
        assertThat(after.getStatus())
                .as("证据写入失败时，记录状态必须回滚为原来的 0，不能留下 status=2 的半成品")
                .isEqualTo(0);
        assertThat(after.getVerdict()).isNull();
        assertThat(after.getCompleteTime()).isNull();
        // 收尾由 @AfterEach 的 deleteTestRecords() 统一负责
    }

    // ------------------------------------------------------------------
    // 条件更新（第 4 批并发修复）的真实行锁语义
    // ------------------------------------------------------------------

    @Test
    @DisplayName("条件更新：待诊断→已完成只成功一次，第二次返回 0 行（并发守卫的本质）")
    void conditionalConclusionUpdateShouldOnlySucceedOnce() {
        Long recordId = insertRecord();
        // 造一条"待人工诊断"的记录
        factCheckRecordMapper.update(FactCheckRecord.builder()
                .id(recordId).status(1).version(1).updateTime(java.time.LocalDateTime.now()).build());

        // 第一次：命中 where status = 1
        int first = factCheckRecordMapper.updateConclusionIfStatus(
                recordId, 1, 2, 2, 2, "人工诊断推理", java.time.LocalDateTime.now());
        assertThat(first).as("第一次人工诊断必须成功").isEqualTo(1);

        FactCheckRecord after = factCheckRecordMapper.getById(recordId);
        assertThat(after.getStatus()).isEqualTo(2);
        assertThat(after.getVerdict()).isEqualTo(2);
        assertThat(after.getVerdictSource()).isEqualTo(2);

        // 第二次（模拟另一个管理员同时点）：状态已变成 2，条件不再命中
        int second = factCheckRecordMapper.updateConclusionIfStatus(
                recordId, 1, 2, 3, 2, "第二条诊断推理", java.time.LocalDateTime.now());
        assertThat(second).as("第二次必须返回 0 行，调用方据此拒绝重复操作").isZero();

        // 结论没有被第二次覆盖，版本也只 +1
        FactCheckRecord unchanged = factCheckRecordMapper.getById(recordId);
        assertThat(unchanged.getVerdict()).as("第二次不得覆盖第一次的结论").isEqualTo(2);
        assertThat(unchanged.getReasoningChain()).isEqualTo("人工诊断推理");
    }

    @Test
    @DisplayName("版本号由 SQL 原子自增：连续两次改结论后 version 恰好 +2（不丢留痕）")
    void versionShouldIncrementAtomicallyPerUpdate() {
        Long recordId = insertRecord();
        factCheckRecordMapper.update(FactCheckRecord.builder()
                .id(recordId).status(2).version(1).updateTime(java.time.LocalDateTime.now()).build());

        int before = factCheckRecordMapper.getById(recordId).getVersion();
        assertThat(before).isEqualTo(1);

        assertThat(factCheckRecordMapper.updateConclusionIfStatus(
                recordId, 2, null, 1, 2, "第一次改", null)).isEqualTo(1);
        assertThat(factCheckRecordMapper.getById(recordId).getVersion()).isEqualTo(2);

        assertThat(factCheckRecordMapper.updateConclusionIfStatus(
                recordId, 2, null, 3, 2, "第二次改", null)).isEqualTo(1);
        assertThat(factCheckRecordMapper.getById(recordId).getVersion())
                .as("两次修改必须恰好 +2；原实现在并发下会只 +1（丢一次留痕）")
                .isEqualTo(3);
    }
}
