package com.factcheck.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.factcheck.constant.MessageConstant;
import com.factcheck.context.BaseContext;
import com.factcheck.dto.ContentSafetyResult;
import com.factcheck.entity.ContentSafetyLog;
import com.factcheck.exception.BaseException;
import com.factcheck.mapper.ContentSafetyLogMapper;
import com.factcheck.properties.ContentSafetyProperties;
import com.factcheck.service.safety.ContentSafetyChecker;
import com.factcheck.service.safety.LocalRuleContentSafetyChecker;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link ContentSafetyServiceImpl} 的处置策略与审核日志测试
 *
 * <p>锁定的是"判定 → 写日志 → 处置"这条链路的契约：BLOCK 必须抛业务异常且日志记 1；
 * REVIEW 必须**放行**（这是本项目的核心取舍）且日志留痕；审核器异常必须 fail-open，
 * 不能因为审核不可用就让用户提交不了内容。</p>
 *
 * <p>完全离线：不启动 Spring，Mapper 用 Mockito mock，审核器用可控的假实现。</p>
 */
@ExtendWith(MockitoExtension.class)
class ContentSafetyServiceImplTest {

    private static final String CLAIM = "武汉又出现新型病毒了，大家不要出门";

    @Mock
    private ContentSafetyLogMapper contentSafetyLogMapper;

    @Mock
    private ObjectProvider<ContentSafetyChecker> checkerProvider;

    private final ContentSafetyProperties properties = new ContentSafetyProperties();

    @AfterEach
    void clearContext() {
        BaseContext.removeCurrentId();
    }

    /**
     * 构造被测服务；checker 为 null 表示容器里没有任何审核器实现
     */
    private ContentSafetyServiceImpl buildService(ContentSafetyChecker checker) {
        when(checkerProvider.stream()).thenReturn(checker == null ? Stream.empty() : Stream.of(checker));
        return new ContentSafetyServiceImpl(contentSafetyLogMapper, properties, checkerProvider);
    }

    private ContentSafetyLog captureLog() {
        ArgumentCaptor<ContentSafetyLog> captor = ArgumentCaptor.forClass(ContentSafetyLog.class);
        verify(contentSafetyLogMapper).insert(captor.capture());
        return captor.getValue();
    }

    /**
     * 可控的假审核器
     */
    private static final class FakeChecker implements ContentSafetyChecker {

        private final ContentSafetyResult result;
        private final RuntimeException failure;

        private FakeChecker(ContentSafetyResult result, RuntimeException failure) {
            this.result = result;
            this.failure = failure;
        }

        static FakeChecker returning(ContentSafetyResult result) {
            return new FakeChecker(result, null);
        }

        static FakeChecker failing(RuntimeException failure) {
            return new FakeChecker(null, failure);
        }

        @Override
        public String name() {
            return "local";
        }

        @Override
        public ContentSafetyResult checkText(String text) {
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        @Override
        public ContentSafetyResult checkImage(String imageUrl) {
            return checkText(imageUrl);
        }
    }

    // ------------------------------------------------------------------
    // BLOCK：拒绝 + 日志记 1
    // ------------------------------------------------------------------

    @Test
    @DisplayName("BLOCK → 抛 BaseException(内容涉嫌违规)，审核日志 result=1 且记录类别")
    void shouldRejectBlockedContentAndLogIt() {
        ContentSafetyServiceImpl service = buildService(
                FakeChecker.returning(ContentSafetyResult.block("色情招嫖", "约炮 + 加微信")));

        assertThatThrownBy(() -> service.checkText(CLAIM))
                .isInstanceOf(BaseException.class)
                .hasMessage(MessageConstant.CONTENT_ILLEGAL);

        ContentSafetyLog log = captureLog();
        assertThat(log.getResult()).isEqualTo(1);
        assertThat(log.getCheckType()).isEqualTo("text");
        assertThat(log.getSourceType()).isEqualTo(1);
        assertThat(log.getReason()).contains("色情招嫖").contains("约炮");
        assertThat(log.getContentHash()).hasSize(64);
    }

    // ------------------------------------------------------------------
    // REVIEW：放行 + 留痕（本项目的核心取舍）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("REVIEW → **放行不抛异常**，审核日志 result=0 且记录敏感类别")
    void shouldAllowReviewedContentButKeepLog() {
        ContentSafetyServiceImpl service = buildService(
                FakeChecker.returning(ContentSafetyResult.review("敏感话题", "病毒")));

        ContentSafetyResult result = service.checkText(CLAIM);

        assertThat(result.isReview()).isTrue();
        ContentSafetyLog log = captureLog();
        assertThat(log.getResult()).isEqualTo(0);
        assertThat(log.getReason()).contains("敏感话题");
    }

    @Test
    @DisplayName("PASS → 放行，审核日志 result=0 且原因为“通过”")
    void shouldLogPassedContent() {
        ContentSafetyServiceImpl service = buildService(FakeChecker.returning(ContentSafetyResult.pass()));

        service.checkText("地球围绕太阳公转");

        ContentSafetyLog log = captureLog();
        assertThat(log.getResult()).isEqualTo(0);
        assertThat(log.getReason()).isEqualTo("通过");
    }

    // ------------------------------------------------------------------
    // 降级与容错
    // ------------------------------------------------------------------

    @Test
    @DisplayName("审核器抛异常 → fail-open（放行）并写一条“异常已放行”的日志")
    void shouldFailOpenWhenCheckerThrows() {
        ContentSafetyServiceImpl service = buildService(
                FakeChecker.failing(new IllegalStateException("云端审核不可用")));

        ContentSafetyResult result = service.checkText(CLAIM);

        assertThat(result.isBlocked()).isFalse();
        ContentSafetyLog log = captureLog();
        assertThat(log.getResult()).isEqualTo(0);
        assertThat(log.getReason()).contains("审核器异常已放行");
    }

    @Test
    @DisplayName("审核日志写入失败 → 不影响主流程（不抛异常）")
    void shouldNotBreakWhenLogInsertFails() {
        when(contentSafetyLogMapper.insert(any(ContentSafetyLog.class)))
                .thenThrow(new RuntimeException("数据库不可用"));
        ContentSafetyServiceImpl service = buildService(
                FakeChecker.returning(ContentSafetyResult.block("色情招嫖", "约炮")));

        // 仍然是业务异常（内容被拒），而不是日志异常
        assertThatThrownBy(() -> service.checkText(CLAIM))
                .isInstanceOf(BaseException.class)
                .hasMessage(MessageConstant.CONTENT_ILLEGAL);
    }

    @Test
    @DisplayName("配置的 provider 没有对应实现 → 不拦截也不写日志（启动时已 ERROR 告警）")
    void shouldSkipWhenProviderNotAvailable() {
        properties.setProvider("aliyun");

        ContentSafetyServiceImpl service = buildService(FakeChecker.returning(ContentSafetyResult.pass()));

        assertThat(service.checkText(CLAIM).isBlocked()).isFalse();
        verify(contentSafetyLogMapper, never()).insert(any(ContentSafetyLog.class));
    }

    @Test
    @DisplayName("enabled=false → 整体关闭：不拦截也不写日志")
    void shouldSkipWhenDisabled() {
        properties.setEnabled(false);

        ContentSafetyServiceImpl service = buildService(
                FakeChecker.returning(ContentSafetyResult.block("色情招嫖", "约炮")));

        assertThat(service.checkText(CLAIM).isBlocked()).isFalse();
        verify(contentSafetyLogMapper, never()).insert(any(ContentSafetyLog.class));
    }

    // ------------------------------------------------------------------
    // 图片与用户上下文
    // ------------------------------------------------------------------

    @Test
    @DisplayName("图片审核：空地址直接放行且不写日志；正常地址写 image 类型日志")
    void shouldHandleImageCheck() {
        ContentSafetyServiceImpl service = buildService(FakeChecker.returning(ContentSafetyResult.pass()));

        assertThat(service.checkImage(null).isBlocked()).isFalse();
        assertThat(service.checkImage("  ").isBlocked()).isFalse();
        verify(contentSafetyLogMapper, never()).insert(any(ContentSafetyLog.class));

        service.checkImage("https://bucket.oss-cn-hangzhou.aliyuncs.com/a.jpg");
        ContentSafetyLog log = captureLog();
        assertThat(log.getCheckType()).isEqualTo("image");
        assertThat(log.getSourceType()).isEqualTo(2);
    }

    @Test
    @DisplayName("审核日志带上当前登录用户；未登录时记 0")
    void shouldRecordCurrentUserId() {
        ContentSafetyServiceImpl service = buildService(FakeChecker.returning(ContentSafetyResult.pass()));

        BaseContext.setCurrentId(42L);
        service.checkText("地球围绕太阳公转");
        assertThat(captureLog().getUserId()).isEqualTo(42L);

        BaseContext.removeCurrentId();
        service.checkText("地球围绕太阳公转");
        ArgumentCaptor<ContentSafetyLog> captor = ArgumentCaptor.forClass(ContentSafetyLog.class);
        verify(contentSafetyLogMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues().get(1).getUserId()).isEqualTo(0L);
    }

    // ------------------------------------------------------------------
    // 与真实规则实现的联通
    // ------------------------------------------------------------------

    @Test
    @DisplayName("接入真实的本地规则实现：招揽广告被拒、核心场景放行")
    void shouldWorkWithRealLocalChecker() {
        ContentSafetyServiceImpl service = buildService(new LocalRuleContentSafetyChecker(properties));

        assertThatThrownBy(() -> service.checkText("同城约炮 加微信 abc12345"))
                .isInstanceOf(BaseException.class);

        assertThat(service.checkText(CLAIM).isBlocked()).isFalse();
        verify(contentSafetyLogMapper, times(2)).insert(any(ContentSafetyLog.class));
    }
}
