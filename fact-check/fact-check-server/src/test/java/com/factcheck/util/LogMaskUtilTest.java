package com.factcheck.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LogMaskUtil} 的纯函数测试。
 *
 * <p>这个工具存在的理由是"用户提交的待核查文本不应被整段写进日志"，
 * 因此测试重点不是格式化好不好看，而是**绝不能泄漏原文**：
 * 长文本必须被截断、凭据类字段必须被打码、JSON 只允许输出字段名。</p>
 */
class LogMaskUtilTest {

    @Test
    @DisplayName("preview：null 原样返回，短文本原样返回，长文本截断并标注总长")
    void previewShouldTruncateLongText() {
        assertThat(LogMaskUtil.preview(null)).isNull();
        assertThat(LogMaskUtil.preview("")).isEmpty();
        assertThat(LogMaskUtil.preview("网传喝醋可以预防感冒")).isEqualTo("网传喝醋可以预防感冒");

        String longText = "网传".repeat(50); // 100 字符
        String previewed = LogMaskUtil.preview(longText);
        assertThat(previewed).hasSizeLessThan(longText.length());
        assertThat(previewed).contains("共 100 字符");
        // 关键：不能等于原文
        assertThat(previewed).isNotEqualTo(longText);
    }

    @Test
    @DisplayName("preview：恰好等于上限时不截断（边界）")
    void previewShouldKeepBoundaryLength() {
        String exact = "A".repeat(LogMaskUtil.PREVIEW_MAX);
        assertThat(LogMaskUtil.preview(exact)).isEqualTo(exact);

        String over = "A".repeat(LogMaskUtil.PREVIEW_MAX + 1);
        assertThat(LogMaskUtil.preview(over)).contains("共 " + (LogMaskUtil.PREVIEW_MAX + 1) + " 字符");
    }

    @Test
    @DisplayName("mask：短值全部打码（不能出现「看起来像打码其实等于原文」）")
    void maskShouldFullyHideShortValues() {
        assertThat(LogMaskUtil.mask(null)).isNull();
        assertThat(LogMaskUtil.mask("")).isEmpty();
        assertThat(LogMaskUtil.mask("abc")).isEqualTo("***");
        assertThat(LogMaskUtil.mask("abcdefgh")).isEqualTo("***");
    }

    @Test
    @DisplayName("mask：长值保留首尾各 4 字符，中间打码")
    void maskShouldKeepHeadAndTail() {
        String masked = LogMaskUtil.mask("openid-1234567890-abcdef");
        assertThat(masked).startsWith("open");
        assertThat(masked).endsWith("cdef");
        assertThat(masked).contains("***");
        assertThat(masked.length()).isLessThan("openid-1234567890-abcdef".length());
    }

    @Test
    @DisplayName("keysOfJson：只输出顶层字段名，绝不输出取值")
    void keysOfJsonShouldNotLeakValues() {
        String json = "{\"can_judge\":true,\"verdict\":\"FALSE\","
                + "\"reasoning_chain\":\"这是很长的推理链内容\",\"evidence\":[{\"url\":\"https://x\"}]}";

        String keys = LogMaskUtil.keysOfJson(json);

        assertThat(keys).contains("can_judge").contains("verdict").contains("reasoning_chain").contains("evidence");
        assertThat(keys).doesNotContain("FALSE");
        assertThat(keys).doesNotContain("这是很长的推理链内容");
        assertThat(keys).doesNotContain("https://x");
    }

    @Test
    @DisplayName("keysOfJson：解析不出字段时退化为 preview（不能因日志格式化而抛异常）")
    void keysOfJsonShouldFallbackGracefully() {
        assertThat(LogMaskUtil.keysOfJson(null)).isNull();
        assertThat(LogMaskUtil.keysOfJson("")).isEmpty();
        // 非法 JSON：不抛异常即可
        assertThat(LogMaskUtil.keysOfJson("not-a-json")).isNotNull();
    }

    @Test
    @DisplayName("maskSensitiveJsonFields：openid / session_key 被打码，其余字段保留")
    void maskSensitiveJsonFieldsShouldOnlyMaskListedFields() {
        String json = "{\"openid\":\"oABCDEFGHIJKLMNOP\",\"session_key\":\"SKabcdefghijklmn\","
                + "\"unionid\":\"uABCDEFGHIJKLMNOP\",\"errcode\":0}";

        String masked = LogMaskUtil.maskSensitiveJsonFields(json, "openid", "session_key", "unionid");

        assertThat(masked).contains("\"openid\":\"oABC***MNOP\"");
        assertThat(masked).contains("\"session_key\":\"SKab***klmn\"");
        assertThat(masked).contains("\"unionid\":\"uABC***MNOP\"");
        // 非敏感字段不动
        assertThat(masked).contains("\"errcode\":0");
        // 原文中的完整凭据不得残留
        assertThat(masked).doesNotContain("oABCDEFGHIJKLMNOP");
        assertThat(masked).doesNotContain("SKabcdefghijklmn");
    }

    @Test
    @DisplayName("maskSensitiveJsonFields：字段不存在、入参为 null 都不得抛异常")
    void maskSensitiveJsonFieldsShouldBeSafe() {
        assertThat(LogMaskUtil.maskSensitiveJsonFields(null, "openid")).isNull();
        assertThat(LogMaskUtil.maskSensitiveJsonFields("{}")).isEqualTo("{}");
        assertThat(LogMaskUtil.maskSensitiveJsonFields("{}", (String[]) null)).isEqualTo("{}");
        assertThat(LogMaskUtil.maskSensitiveJsonFields("{\"a\":\"b\"}", "nothere"))
                .isEqualTo("{\"a\":\"b\"}");
    }

    @Test
    @DisplayName("sizeOf：只输出规模，不输出内容")
    void sizeOfShouldOnlyReportCount() {
        assertThat(LogMaskUtil.sizeOf(Arrays.asList(1, 2, 3))).isEqualTo("3 条");
        assertThat(LogMaskUtil.sizeOf((java.util.Collection<?>) null)).isEqualTo("null");

        Map<String, String> map = new LinkedHashMap<>();
        map.put("text", "用户原文");
        assertThat(LogMaskUtil.sizeOf(map)).isEqualTo("1 项");
        assertThat(LogMaskUtil.sizeOf(map)).doesNotContain("用户原文");
    }
}
