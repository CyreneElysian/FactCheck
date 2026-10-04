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

    /**
     * 证据字段长度上限，必须与 sql/01_fact_check_schema.sql 的 t_evidence 列定义一致。
     * 改列定义时**同时**改这里，否则又会退化成"超长 → 整批回滚"。
     */
    static final int MAX_TITLE_LENGTH = 500;   // title  varchar(500)
    static final int MAX_URL_LENGTH = 500;     // url    varchar(500)
    static final int MAX_SOURCE_LENGTH = 100;  // source varchar(100)

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
        // 证据落库失败**不能**拖垮结论（见 saveEvidence 的注释）：
        // 用户要的是"这条消息是真是假"，证据只是佐证；宁可少几条证据，也不能让整次检测白跑。
        try {
            saveEvidence(recordId, result);
        } catch (Exception e) {
            log.error("保存证据失败（结论仍保留）：recordId={}, 证据数={}",
                    recordId, result.getEvidence() == null ? 0 : result.getEvidence().size(), e);
        }
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
     *
     * <h3>为什么必须先裁剪长度（真实缺陷，recordId=27）</h3>
     * Agent 返回的字段直接对应 {@code t_evidence} 的列，而列有长度上限：
     * {@code title} / {@code url} 是 {@code varchar(500)}、{@code source} 是 {@code varchar(100)}。
     * 此前原样写入，于是碰上一条超长 URL 就整批失败：
     * <pre>
     * MysqlDataTruncation: Data too long for column 'url' at row 9
     *   at DetectResultWriter.saveEvidence(DetectResultWriter.java:185)
     * </pre>
     * 触发它的是一条 Facebook 链接 —— **百分号编码的中文**（{@code %E9%80%99...}）
     * 把 URL 撑到 500 字符以上。后果被放大得很严重：
     * 批量 INSERT 是单条语句，**第 9 行超长 → 10 条证据全部回滚 → writeJudged 事务整体失败
     * → 记录被判"检测失败" → 用户只看到"检测服务暂时不可用"**，
     * 而 AI 的结论其实早就拿到了，纯粹因为一条 URL 太长而被丢弃。
     *
     * <p>裁剪策略（按字段语义区分，不是一律截断了事）：</p>
     * <ul>
     *   <li>{@code title} / {@code source}：**截断**。它们是展示文本，截断后仍可读、仍有信息量。</li>
     *   <li>{@code url}：**整条置空**，不截断。截断的 URL 必然指向错误地址，而前端是
     *       {@code <el-link :href="row.url">}（DetectView.vue）—— 一个点不开/点错地方的死链
     *       比"没有链接"更糟糕。置空后前端会退化成纯文本标题（同文件已有 {@code v-else} 分支），
     *       证据的标题与摘要仍然保留。</li>
     * </ul>
     */
    private void saveEvidence(Long recordId, AgentClient.DetectResult result) {
        if (result.getEvidence() == null || result.getEvidence().isEmpty()) {
            return;
        }
        List<Evidence> evidenceList = new ArrayList<>();
        for (AgentClient.DetectEvidence e : result.getEvidence()) {
            String title = truncate(e.getTitle(), MAX_TITLE_LENGTH, "title", recordId);
            String source = truncate(e.getSource(), MAX_SOURCE_LENGTH, "source", recordId);
            String url = keepUrlOrBlank(e.getUrl(), recordId);
            if (isBlank(title) && isBlank(url)) {
                log.warn("丢弃一条无标题且无链接的证据：recordId={}", recordId);
                continue;
            }
            evidenceList.add(Evidence.builder()
                    .recordId(recordId)
                    .title(title)
                    .url(url)
                    .snippet(e.getSnippet())
                    .source(source)
                    .sourceCredibility(e.getSourceCredibility())
                    .relevanceScore(e.getRelevanceScore())
                    .sourceType(1)
                    .createTime(LocalDateTime.now())
                    .build());
        }
        if (evidenceList.isEmpty()) {
            log.warn("证据全部被裁剪/丢弃后为空，跳过写入：recordId={}", recordId);
            return;
        }
        // 先清掉该记录的旧证据，再写入本次结果（两者同一个事务，不会出现"清空但没写入"的中间态）。
        //
        // 这里原本考虑改成"先插后删"以防插入失败丢掉旧证据，但那样需要能区分新旧行，
        // 而 t_evidence 没有可用的批次标识（同一批 create_time 相同），
        // 强行按时间戳保留旧行会在同一秒内误判。保持"先删后插"更简单也更符合语义：
        // 检测结果本就是整体替换，不是增量追加。
        evidenceMapper.deleteByRecordId(recordId);
        evidenceMapper.insertBatch(evidenceList);
    }

    /**
     * URL 超长时置空（而不是截断）：截断的链接一定指向错误地址，
     * 前端会把它渲染成可点击的链接，反而误导用户。
     */
    private static String keepUrlOrBlank(String url, Long recordId) {
        if (url == null || url.length() <= MAX_URL_LENGTH) {
            return url;
        }
        log.warn("证据链接超长（超过 {}），已置空以留出标题与摘要：recordId={}, 原长度={}",
                MAX_URL_LENGTH, recordId, url.length());
        return null;
    }

    /**
     * 按列长度上限裁剪；超长时打 WARN（含原始长度与字段名，便于回查是哪条数据）
     */
    private static String truncate(String value, int maxLength, String field, Long recordId) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        log.warn("证据字段超长已裁剪：recordId={}, field={}, 原长度={}, 上限={}",
                recordId, field, value.length(), maxLength);
        return value.substring(0, maxLength);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
