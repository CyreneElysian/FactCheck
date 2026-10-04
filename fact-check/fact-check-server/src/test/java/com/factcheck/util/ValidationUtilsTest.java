package com.factcheck.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.factcheck.exception.BaseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ValidationUtils} 单元测试
 *
 * <p>这些规则是各接口的"入口防线"，一旦放松（例如不再 trim、不再限制分页大小），
 * 用户会重新看到"未知错误"或让整表被拉进内存，因此逐条锁定行为。</p>
 */
class ValidationUtilsTest {

    // ------------------------------------------------------------------
    // requireText
    // ------------------------------------------------------------------

    @Test
    @DisplayName("requireText：返回去空白后的文本（便于调用方直接落库）")
    void shouldReturnTrimmedText() {
        assertThat(ValidationUtils.requireText("  武汉又出现新型病毒了  ", "检测内容", 100))
                .isEqualTo("武汉又出现新型病毒了");
    }

    @Test
    @DisplayName("requireText：null / 空串 / 纯空白都判为“不能为空”")
    void shouldRejectBlankText() {
        for (String blank : new String[]{null, "", "   ", "\t\n"}) {
            assertThatThrownBy(() -> ValidationUtils.requireText(blank, "检测内容", 100))
                    .isInstanceOf(BaseException.class)
                    .hasMessage("检测内容不能为空");
        }
    }

    @Test
    @DisplayName("requireText：超长时给出可读提示，且提示里带字段名与上限")
    void shouldRejectTooLongText() {
        String text = "啊".repeat(11);

        assertThatThrownBy(() -> ValidationUtils.requireText(text, "分类", 10))
                .isInstanceOf(BaseException.class)
                .hasMessage("分类长度不能超过 10 个字符");
    }

    @Test
    @DisplayName("requireText：正好等于上限时通过（边界闭区间）")
    void shouldAcceptExactMaxLength() {
        String text = "啊".repeat(10);

        assertThat(ValidationUtils.requireText(text, "分类", 10)).hasSize(10);
    }

    // ------------------------------------------------------------------
    // optionalText
    // ------------------------------------------------------------------

    @Test
    @DisplayName("optionalText：null / 空白返回 null，非空返回去空白后的值")
    void shouldHandleOptionalText() {
        assertThat(ValidationUtils.optionalText(null, "昵称", 10)).isNull();
        assertThat(ValidationUtils.optionalText("   ", "昵称", 10)).isNull();
        assertThat(ValidationUtils.optionalText("  小明  ", "昵称", 10)).isEqualTo("小明");
    }

    @Test
    @DisplayName("optionalText：非空时同样校验长度")
    void shouldRejectTooLongOptionalText() {
        assertThatThrownBy(() -> ValidationUtils.optionalText("123456", "昵称", 5))
                .isInstanceOf(BaseException.class)
                .hasMessage("昵称长度不能超过 5 个字符");
    }

    // ------------------------------------------------------------------
    // requireOneOf
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] verdict={0}")
    @DisplayName("requireOneOf：合法取值通过")
    @ValueSource(ints = {1, 2, 3})
    void shouldAcceptAllowedValues(int value) {
        ValidationUtils.requireOneOf(value, "结论", 1, 2, 3);
    }

    @Test
    @DisplayName("requireOneOf：非法取值与 null 都被拒绝")
    void shouldRejectDisallowedValues() {
        assertThatThrownBy(() -> ValidationUtils.requireOneOf(4, "结论", 1, 2, 3))
                .isInstanceOf(BaseException.class)
                .hasMessage("结论取值不合法");
        assertThatThrownBy(() -> ValidationUtils.requireOneOf(null, "结论", 1, 2, 3))
                .isInstanceOf(BaseException.class)
                .hasMessage("结论不能为空");
    }

    // ------------------------------------------------------------------
    // requirePaging
    // ------------------------------------------------------------------

    @Test
    @DisplayName("requirePaging：正常分页参数通过")
    void shouldAcceptValidPaging() {
        ValidationUtils.requirePaging(1, 10);
        ValidationUtils.requirePaging(999, ValidationUtils.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("requirePaging：page<1 被拒（page=0 会被 PageHelper 当成不分页而返回全表）")
    void shouldRejectInvalidPage() {
        for (Integer page : new Integer[]{null, 0, -1}) {
            assertThatThrownBy(() -> ValidationUtils.requirePaging(page, 10))
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining("页码不正确");
        }
    }

    @Test
    @DisplayName("requirePaging：pageSize 越界被拒（过大同样会把整表拉进内存）")
    void shouldRejectInvalidPageSize() {
        for (Integer size : new Integer[]{null, 0, -5, ValidationUtils.MAX_PAGE_SIZE + 1}) {
            assertThatThrownBy(() -> ValidationUtils.requirePaging(1, size))
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining("每页条数不正确");
        }
    }

    // ------------------------------------------------------------------
    // optionalHttpUrl
    // ------------------------------------------------------------------

    @Test
    @DisplayName("optionalHttpUrl：为空返回 null；非 http(s) 一律拒绝")
    void shouldValidateOptionalHttpUrl() {
        assertThat(ValidationUtils.optionalHttpUrl(null, "图片地址", 255)).isNull();
        assertThat(ValidationUtils.optionalHttpUrl("  ", "图片地址", 255)).isNull();
        assertThat(ValidationUtils.optionalHttpUrl("https://a.example.com/x.jpg", "图片地址", 255))
                .isEqualTo("https://a.example.com/x.jpg");

        for (String bad : new String[]{"ftp://a.com/x", "javascript:alert(1)", "/local/x.jpg"}) {
            assertThatThrownBy(() -> ValidationUtils.optionalHttpUrl(bad, "图片地址", 255))
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining("必须以 http:// 或 https:// 开头");
        }
    }
}
