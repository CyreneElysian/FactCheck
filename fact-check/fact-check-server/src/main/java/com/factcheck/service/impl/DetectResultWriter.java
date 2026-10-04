package com.factcheck.service.impl;

import com.factcheck.constant.HotScoreConstant;
import com.factcheck.entity.Evidence;
import com.factcheck.entity.FactCheckRecord;
import com.factcheck.entity.Message;
import com.factcheck.mapper.EvidenceMapper;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.mapper.MessageMapper;
import com.factcheck.service.AgentClient;
import com.factcheck.service.HotRankService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 检测结果写回器（**短事务边界**）
 *
 * <h3>为什么需要单独的 Bean，而不是把 {@code @Transactional} 留在 asyncDetect 上</h3>
 * 原先 {@code DetectServiceImpl.asyncDetect} 同时标了
 * {@code @Async("detectExecutor")} 与 {@code @Transactional}，而方法体第一件事就是
 * 调用 Python Agent 的 HTTP 接口（读超时 90 秒）。这带来两个真实问题：
 *
 * <ol>
 *   <li><b>长事务占着数据库连接</b>：整个 HTTP 往返期间事务一直开着、连接一直被持有。
 *       检测线程池是 core=5/max=20，20 个并发检测就能把连接池占满，导致全站请求阻塞。</li>
 *   <li><b>异常会在 catch 块之后仍然回滚掉一切</b>：如果异常从 catch 块逃逸（或在回滚标记已置位后
 *       又执行写库），Spring 会把整个事务回滚，于是"记录永远停在 status=0（检测中）"，
 *       成功路径写下的结论、证据、站内信也一并消失，用户永远等不到结果。</li>
 * </ol>
 *
 * <p>拆开的做法：Agent 调用留在 {@code DetectServiceImpl.asyncDetect}（**无事务**），
 * 所有写库动作集中到本类的方法里、各自开一个**短事务**。
 * 独立 Bean 是必须的——同一个类内部自调用不会走代理，{@code @Transactional} 会静默失效
 * （这正是 P5-49 那类"注解看起来加了其实没生效"的坑）。</p>
 */
@Service
@Slf4j
public class DetectResultWriter {

    @Autowired
    private FactCheckRecordMapper factCheckRecordMapper;
    @Autowired
    private EvidenceMapper evidenceMapper;
    @Autowired
    private MessageMapper messageMapper;
    @Autowired
    private HotRankService hotRankService;

    /**
     * 写回"检测成功"结果：更新记录结论 + 保存证据 + 发站内信
     *
     * @return true 表示已写入
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean writeJudged(Long recordId, Long userId, Integer verdict, Double confidence,
                               String reasoningChain, AgentClient.DetectResult result) {
        FactCheckRecord update = FactCheckRecord.builder()
                .id(recordId)
                .status(2)
                .verdict(verdict)
                .verdictSource(1)
                .confidence(confidence == null ? null : BigDecimal.valueOf(confidence))
                .reasoningChain(reasoningChain)
                .completeTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        factCheckRecordMapper.update(update);
        saveEvidence(recordId, result);
        messageMapper.insert(Message.builder()
                .userId(userId)
                .type(1)
                .title("检测完成")
                .content("您的消息已完成检测，点击查看结论")
                .relatedId(recordId)
                .isRead(0)
                .createTime(LocalDateTime.now())
                .build());
        log.info("检测成功结果已写回：recordId={}, verdict={}", recordId, verdict);
        return true;
    }

    /**
     * 写回"检测失败"结果（status=3，可重试）：更新记录 + 发失败站内信
     *
     * @param manualReason 人工可读的失败原因；为 null 时用默认文案
     * @return true 表示已写入
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean writeFailed(Long recordId, Long userId, String manualReason) {
        String reason = manualReason == null ? "检测服务异常，请稍后重试" : manualReason;
        factCheckRecordMapper.update(FactCheckRecord.builder()
                .id(recordId)
                .status(3)
                .aiSummary(reason)
                .updateTime(LocalDateTime.now())
                .build());
        messageMapper.insert(Message.builder()
                .userId(userId)
                .type(6)
                .title("检测失败")
                .content("很抱歉，检测服务暂时不可用，请稍后重试")
                .relatedId(recordId)
                .isRead(0)
                .createTime(LocalDateTime.now())
                .build());
        log.warn("检测失败结果已写回：recordId={}, reason={}", recordId, reason);
        return true;
    }

    /**
     * 写回"AI 无法判断、转人工诊断"结果（status=1）：更新记录 + 发站内信
     *
     * @return true 表示已写入
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean writePendingHuman(Long recordId, Long userId, String humanReason) {
        factCheckRecordMapper.update(FactCheckRecord.builder()
                .id(recordId)
                .status(1)
                .aiSummary(humanReason == null ? "AI 无法判断，转人工诊断" : humanReason)
                .updateTime(LocalDateTime.now())
                .build());
        messageMapper.insert(Message.builder()
                .userId(userId)
                .type(2)
                .title("消息已进入人工诊断队列")
                .content("AI 暂时无法判断您的消息，已转交管理员人工诊断，请稍后查看")
                .relatedId(recordId)
                .isRead(0)
                .createTime(LocalDateTime.now())
                .build());
        log.info("转人工结果已写回：recordId={}", recordId);
        return true;
    }

    /**
     * 兜底：把记录直接标记为"检测失败"（仅改状态，不发站内信）
     *
     * <p>用于"连失败站内信都发不出去"的极端情况——此时事务可能已经不可用，
     * 因此本方法**独立开事务**，且自身抛异常时只记日志，绝不再向上抛。</p>
     */
    @Transactional(rollbackFor = Exception.class, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void markFailedQuietly(Long recordId) {
        try {
            factCheckRecordMapper.update(FactCheckRecord.builder()
                    .id(recordId)
                    .status(3)
                    .updateTime(LocalDateTime.now())
                    .build());
            log.warn("已把记录标记为检测失败（兜底）：recordId={}", recordId);
        } catch (Exception e) {
            // 兜底路径不能再抛，否则会掩盖原始异常
            log.error("兜底标记检测失败也失败了：recordId={}", recordId, e);
        }
    }

    /**
     * 把 Agent 返回的证据落库（先按记录清空，再批量写入）
     */
    private void saveEvidence(Long recordId, AgentClient.DetectResult result) {
        if (result.getEvidence() == null || result.getEvidence().isEmpty()) {
            return;
        }
        evidenceMapper.deleteByRecordId(recordId);
        List<Evidence> evidenceList = new ArrayList<>();
        for (AgentClient.DetectEvidence e : result.getEvidence()) {
            evidenceList.add(Evidence.builder()
                    .recordId(recordId)
                    .title(e.getTitle())
                    .url(e.getUrl())
                    .snippet(e.getSnippet())
                    .source(e.getSource())
                    .sourceCredibility(e.getSourceCredibility())
                    .relevanceScore(e.getRelevanceScore())
                    .sourceType(1)
                    .createTime(LocalDateTime.now())
                    .build());
        }
        evidenceMapper.insertBatch(evidenceList);
    }
}
