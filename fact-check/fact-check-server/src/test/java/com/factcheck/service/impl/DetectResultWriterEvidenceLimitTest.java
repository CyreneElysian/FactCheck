package com.factcheck.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.factcheck.entity.Evidence;
import com.factcheck.mapper.EvidenceMapper;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.mapper.MessageMapper;
import com.factcheck.service.AgentClient;
import com.factcheck.service.HotRankService;
import java.util.ArrayList;
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
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 证据落库的**长度防御**与**故障隔离**回归测试。
 *
 * <h3>要钉住的真实缺陷（recordId=27，2026-10-04 实测）</h3>
 * <pre>
 * MysqlDataTruncation: Data too long for column 'url' at row 9
 *   at DetectResultWriter.saveEvidence(DetectResultWriter.java:185)
 * </pre>
 * 触发者是一条 Facebook 链接 —— <b>百分号编码的中文</b>（{@code %E9%80%99...}）
 * 把 URL 撑到 500 字符以上。后果被批量插入放大成灾难：
 * <ol>
 *   <li>批量 INSERT 是**单条语句**，第 9 行超长 → 10 条证据全部回滚；</li>
 *   <li>{@code writeJudged} 的事务随之失败 → 记录被判"检测失败"（status=3）；</li>
 *   <li>用户只看到"检测服务暂时不可用" —— 而 AI 的结论**早就拿到了**，
 *       纯粹因为一条 URL 太长而被丢弃。</li>
 * </ol>
 *
 * <p>所以本测试守两条底线：<b>超长字段必须被裁剪而不是让整批失败</b>；
 * <b>即使证据彻底写不进去，结论也必须保住</b>。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DetectResultWriterEvidenceLimitTest {

    private static final Long RECORD_ID = 27L;
    private static final Long USER_ID = 1L;

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

    /**
     * 造一条证据；传 null 表示该字段不设置
     */
    private AgentClient.DetectEvidence evidence(String title, String url, String source) {
        AgentClient.DetectEvidence e = new AgentClient.DetectEvidence();
        e.setTitle(title);
        e.setUrl(url);
        e.setSource(source);
        e.setSnippet("摘要");
        return e;
    }

    private AgentClient.DetectResult resultWith(List<AgentClient.DetectEvidence> list) {
        AgentClient.DetectResult result = new AgentClient.DetectResult();
        result.setEvidence(list);
        return result;
    }

    private List<Evidence> captureInserted() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Evidence>> captor = ArgumentCaptor.forClass(List.class);
        verify(evidenceMapper).insertBatch(captor.capture());
        return captor.getValue();
    }

    /** 复刻触发缺陷的那条 URL：百分号编码中文，长度远超 varchar(500) */
    private static String oversizedFacebookUrl() {
        StringBuilder sb = new StringBuilder("https://www.facebook.com/ooihean/posts/");
        for (int i = 0; i < 30; i++) {
            sb.append("%E9%80%99%E7%A8%AE%E7%9D%A1%E7%9C%A0%E5%9E%8B%E6%85%8B");
        }
        sb.append("/1032464018351602");
        return sb.toString();
    }

    @Test
    @DisplayName("回归 recordId=27：超长 URL 被置空而不是让整批证据失败，其余证据全部保留")
    void oversizedUrlShouldBeBlankedNotFail() {
        String url = oversizedFacebookUrl();
        assertThat(url.length()).as("构造的 URL 必须真的超长，否则这条回归测试是假的").isGreaterThan(500);

        // 10 条证据，第 9 条（下标 8）是超长 URL —— 与真实故障一致
        List<AgentClient.DetectEvidence> list = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            list.add(i == 8
                    ? evidence("熬夜致癌吗", url, "www.facebook.com")
                    : evidence("证据" + i, "https://example.com/" + i, "example.com"));
        }
        writer.writeJudged(RECORD_ID, USER_ID, 2, 0.9D, "置信度不足", resultWith(list));

        List<Evidence> inserted = captureInserted();
        assertThat(inserted).as("10 条证据应当全部保留（只处理超长的那一个字段）").hasSize(10);

        Evidence ninth = inserted.get(8);
        // 关键：**不截断**。截断的链接一定指向错误地址，而前端是 <el-link :href="row.url">，
        // 会渲染成一个点错地方的死链 —— 比没有链接更糟。
        assertThat(ninth.getUrl()).as("超长 URL 应整体置空").isNull();
        assertThat(ninth.getTitle()).as("标题与摘要必须保留，证据仍然可用").isEqualTo("熬夜致癌吗");
        assertThat(ninth.getSnippet()).isEqualTo("摘要");
        assertThat(ninth.getSource()).isEqualTo("www.facebook.com");

        // 未超长的字段不应被改动
        assertThat(inserted.get(0).getUrl()).isEqualTo("https://example.com/0");
    }

    @Test
    @DisplayName("恰好等于上限的 URL 保留原样（边界：off-by-one）")
    void urlExactlyAtLimitShouldBeKept() {
        String url = "https://example.com/" + "u".repeat(DetectResultWriter.MAX_URL_LENGTH - 20);
        assertThat(url).hasSize(DetectResultWriter.MAX_URL_LENGTH);

        writer.writeJudged(RECORD_ID, USER_ID, 2, 0.9D, "r",
                resultWith(List.of(evidence("标题", url, "example.com"))));

        assertThat(captureInserted().get(0).getUrl()).isEqualTo(url);
    }

    @Test
    @DisplayName("超长 title（varchar(500)）同样被裁剪")
    void oversizedTitleShouldBeTruncated() {
        writer.writeJudged(RECORD_ID, USER_ID, 2, 0.9D, "r",
                resultWith(List.of(evidence("标".repeat(800), "https://example.com/a", "example.com"))));

        assertThat(captureInserted().get(0).getTitle()).hasSize(500);
    }

    @Test
    @DisplayName("超长 source（varchar(100)）被裁剪到 100")
    void oversizedSourceShouldBeTruncated() {
        writer.writeJudged(RECORD_ID, USER_ID, 2, 0.9D, "r",
                resultWith(List.of(evidence("标题", "https://example.com/a", "s".repeat(300)))));

        assertThat(captureInserted().get(0).getSource()).hasSize(100);
    }

    @Test
    @DisplayName("恰好等于上限时不裁剪 title/source（边界：off-by-one）")
    void exactLimitShouldNotBeTruncated() {
        String title = "t".repeat(DetectResultWriter.MAX_TITLE_LENGTH);
        String source = "s".repeat(DetectResultWriter.MAX_SOURCE_LENGTH);
        String url = "https://example.com/" + "u".repeat(DetectResultWriter.MAX_URL_LENGTH - 20);

        writer.writeJudged(RECORD_ID, USER_ID, 2, 0.9D, "r",
                resultWith(List.of(evidence(title, url, source))));

        Evidence e = captureInserted().get(0);
        assertThat(e.getTitle()).isEqualTo(title);
        assertThat(e.getSource()).isEqualTo(source);
        assertThat(e.getUrl()).isEqualTo(url);
    }

    @Test
    @DisplayName("证据仍写不进去时，结论必须保留：writeJudged 不能抛异常")
    void conclusionShouldSurviveEvidenceFailure() {
        doThrow(new DataIntegrityViolationException("模拟证据写入仍然失败"))
                .when(evidenceMapper).insertBatch(any());

        assertThatCode(() -> writer.writeJudged(RECORD_ID, USER_ID, 2, 0.88D, "理由",
                resultWith(List.of(evidence("标题", "https://example.com/a", "example.com")))))
                .as("证据落库失败不应把异常抛给调用方 —— 否则整条记录会被判成检测失败")
                .doesNotThrowAnyException();

        // 结论与站内信仍应写入
        verify(factCheckRecordMapper).update(any());
        verify(messageMapper).insert(any());
    }

    @Test
    @DisplayName("无标题且无链接的证据被丢弃，其余照常写入")
    void evidenceWithoutTitleAndUrlShouldBeDropped() {
        writer.writeJudged(RECORD_ID, USER_ID, 2, 0.9D, "r",
                resultWith(List.of(
                        evidence("   ", "  ", "example.com"),
                        evidence("正常标题", "https://example.com/ok", "example.com"))));

        List<Evidence> inserted = captureInserted();
        assertThat(inserted).hasSize(1);
        assertThat(inserted.get(0).getTitle()).isEqualTo("正常标题");
    }

    @Test
    @DisplayName("证据全部为空时不调用 insertBatch（避免写入空记录）")
    void allEvidenceDroppedShouldSkipInsert() {
        writer.writeJudged(RECORD_ID, USER_ID, 2, 0.9D, "r",
                resultWith(List.of(evidence(null, null, null))));

        verify(evidenceMapper, org.mockito.Mockito.never()).insertBatch(any());
    }

    @Test
    @DisplayName("证据为空列表时不清空已有证据（deleteByRecordId 也不该被调用）")
    void emptyEvidenceShouldNotClearExisting() {
        writer.writeJudged(RECORD_ID, USER_ID, 2, 0.9D, "r", resultWith(List.of()));

        verify(evidenceMapper, org.mockito.Mockito.never()).deleteByRecordId(anyLong());
    }
}
