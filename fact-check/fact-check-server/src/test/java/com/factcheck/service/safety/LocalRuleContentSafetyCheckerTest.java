package com.factcheck.service.safety;

import static org.assertj.core.api.Assertions.assertThat;

import com.factcheck.dto.ContentSafetyResult;
import com.factcheck.properties.ContentSafetyProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link LocalRuleContentSafetyChecker} 规则测试
 *
 * <p>最重要的不是"该拦的拦住了"，而是**"不该拦的没被拦"**：这是一个事实核查平台，
 * 用户提交的内容天然包含"病毒""疫情""诈骗""赌博""爆炸"这些词——它们正是待核查的说法本身。
 * 按敏感词直接拒绝会把产品最核心的场景全部挡掉，因此这里用一整组用例锁住"核心场景必须放行"。</p>
 *
 * <p>完全离线：直接 new 出 checker，不启动 Spring。</p>
 */
class LocalRuleContentSafetyCheckerTest {

    private final ContentSafetyProperties properties = new ContentSafetyProperties();

    private final LocalRuleContentSafetyChecker checker = new LocalRuleContentSafetyChecker(properties);

    private ContentSafetyResult check(String text) {
        return checker.checkText(text);
    }

    // ------------------------------------------------------------------
    // 拒绝：违法交易 / 招揽广告（违法词 + 联系方式 双信号）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("色情招嫖：色情词 + 加微信 → 拒绝")
    void shouldBlockPornSolicitation() {
        ContentSafetyResult result = check("同城约炮 加微信 abc12345 快速安排");

        assertThat(result.isBlocked()).isTrue();
        assertThat(result.getCategory()).isEqualTo("色情招嫖");
        assertThat(result.getDetail()).contains("约炮").contains("加微信");
    }

    @Test
    @DisplayName("赌博推广：赌博词 + 联系我 → 拒绝")
    void shouldBlockGamblingPromotion() {
        ContentSafetyResult result = check("澳门赌场真人荷官在线发牌，联系我开户送彩金");

        assertThat(result.isBlocked()).isTrue();
        assertThat(result.getCategory()).isEqualTo("赌博推广");
    }

    @Test
    @DisplayName("诈骗引流：话术 + 名额有限 → 拒绝")
    void shouldBlockFraudSolicitation() {
        ContentSafetyResult result = check("刷单返利，日结，名额有限，速来");

        assertThat(result.isBlocked()).isTrue();
        assertThat(result.getCategory()).isEqualTo("诈骗引流");
    }

    @Test
    @DisplayName("违禁品交易：违禁品词 + 手机号（正则模式）→ 拒绝")
    void shouldBlockIllegalGoodsWithPhonePattern() {
        ContentSafetyResult result = check("长期供应银行卡四件套 电话13812345678");

        assertThat(result.isBlocked()).isTrue();
        assertThat(result.getCategory()).isEqualTo("违禁品交易");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @DisplayName("其他联系方式模式（QQ 号 / 短链）同样算招揽信号")
    @ValueSource(strings = {
            "代开发票 qq:123456789 专业可靠",
            "刷单返利 点击链接 t.cn/abc123 立即领取",
    })
    void shouldTreatContactPatternsAsSolicitation(String text) {
        assertThat(check(text).isBlocked()).isTrue();
    }

    // ------------------------------------------------------------------
    // 核心场景：必须放行（这些是事实核查平台最典型的输入）
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0}")
    @DisplayName("核心场景必须放行：提到敏感/违法词但没有招揽特征时不拦截")
    @ValueSource(strings = {
            "武汉又出现新型病毒了，大家不要出门",
            "网传某地发生恐怖袭击，官方尚未回应",
            "微波炉加热的食物会产生致癌物，欧洲已全面禁止使用微波炉",
            "有人出售枪支，这是真的吗？",
            "毒品有哪些危害？",
            "某平台存在色情直播，是否属实？",
            "网传某赌博网站被查，是真的吗",
    })
    void shouldNotBlockFactCheckClaims(String claim) {
        assertThat(check(claim).isBlocked())
                .as("事实核查平台的核心输入不能被内容安全规则拦掉：%s", claim)
                .isFalse();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @DisplayName("只提到违法词、没有联系方式时一律不拒绝")
    @ValueSource(strings = {
            "刷单返利是不是骗局？",
            "六合彩是什么？",
            "裸聊诈骗的套路有哪些",
            "代开发票违法吗",
    })
    void shouldNotBlockWithoutSolicitationSignal(String text) {
        assertThat(check(text).isBlocked()).isFalse();
    }

    // ------------------------------------------------------------------
    // 放行但留痕
    // ------------------------------------------------------------------

    @Test
    @DisplayName("敏感话题：命中只标记 REVIEW，不拒绝")
    void shouldMarkReviewForSensitiveTopic() {
        ContentSafetyResult result = check("武汉又出现新型病毒了，大家不要出门");

        assertThat(result.isBlocked()).isFalse();
        assertThat(result.isReview()).isTrue();
        assertThat(result.getCategory()).isEqualTo("敏感话题");
        assertThat(result.getDetail()).contains("病毒");
    }

    @Test
    @DisplayName("普通内容：PASS")
    void shouldPassOrdinaryContent() {
        ContentSafetyResult result = check("地球围绕太阳公转");

        assertThat(result.isBlocked()).isFalse();
        assertThat(result.isReview()).isFalse();
        assertThat(result.getLevel()).isEqualTo(ContentSafetyResult.Level.PASS);
    }

    @Test
    @DisplayName("空内容 / null：PASS（不做判定）")
    void shouldPassBlankContent() {
        assertThat(check("").isBlocked()).isFalse();
        assertThat(check("   ").isBlocked()).isFalse();
        assertThat(check(null).isBlocked()).isFalse();
    }

    // ------------------------------------------------------------------
    // 图片地址
    // ------------------------------------------------------------------

    @Test
    @DisplayName("图片地址：正常 https 地址通过")
    void shouldPassNormalImageUrl() {
        assertThat(checker.checkImage("https://bucket.oss-cn-hangzhou.aliyuncs.com/a.jpg").isBlocked()).isFalse();
    }

    @Test
    @DisplayName("图片地址：非 http(s) 协议一律拒绝（防 file:// 等）")
    void shouldBlockNonHttpImageUrl() {
        assertThat(checker.checkImage("file:///etc/passwd").isBlocked()).isTrue();
        assertThat(checker.checkImage("javascript:alert(1)").isBlocked()).isTrue();
        assertThat(checker.checkImage("http:///no-host").isBlocked()).isTrue();
    }

    @Test
    @DisplayName("图片地址：不在域名白名单内 → 拒绝")
    void shouldBlockImageUrlOutsideWhitelist() {
        properties.setImageHostWhitelist(List.of("aliyuncs.com"));

        assertThat(checker.checkImage("https://evil.example.com/a.jpg").isBlocked()).isTrue();
        assertThat(checker.checkImage("https://bucket.oss-cn-hangzhou.aliyuncs.com/a.jpg").isBlocked()).isFalse();
    }

    @Test
    @DisplayName("图片地址：地址文本里的招揽话术也会被识别")
    void shouldCheckImageUrlText() {
        ContentSafetyResult result = checker.checkImage("https://x.example.com/约炮加微信.jpg");

        assertThat(result.isBlocked()).isTrue();
        assertThat(result.getCategory()).isEqualTo("色情招嫖");
    }
}
