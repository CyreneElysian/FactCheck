package com.factcheck.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.factcheck.exception.BaseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ValidationUtils#requireStrongPassword} 的契约测试。
 *
 * <h3>背景</h3>
 * 此前注册与改密**没有任何强度校验**：只要非空且不超过 64 字符，{@code 123456}、
 * {@code password} 都能直接设置。配合"登录按用户名限流 5 次/10 分钟"，弱口令仍可被慢速爆破，
 * 而弱口令是账号失陷的首要原因。
 *
 * <h3>本测试钉住的两条边界（容易做错）</h3>
 * <ol>
 *   <li><b>只在"设置新密码"时校验，不在登录时校验</b>：
 *       {@code login} 不调用本方法。否则历史弱口令账号会在改密前直接无法登录——
 *       那等于用一次"安全加固"把用户锁在门外。本类只测校验规则本身；
 *       "登录不受影响"由 {@code UserServiceImplLoginTest} 保证。</li>
 *   <li><b>不做 trim 加工</b>：首尾空白直接拒绝，而不是悄悄去掉——
 *       密码参与哈希，静默改写会让用户"明明输对了却登不上"。</li>
 * </ol>
 */
class ValidationUtilsStrongPasswordTest {

    @Test
    @DisplayName("合规密码：字母+数字且 ≥8 位 → 通过，并原样返回")
    void shouldAcceptCompliantPassword() {
        assertThat(ValidationUtils.requireStrongPassword("abcd1234", "alice")).isEqualTo("abcd1234");
        assertThat(ValidationUtils.requireStrongPassword("Passw0rdX", null)).isEqualTo("Passw0rdX");
        // 恰好 8 位（下界闭区间）
        assertThatCode(() -> ValidationUtils.requireStrongPassword("a1234567", "bob"))
                .doesNotThrowAnyException();
        // 恰好 64 位（上界闭区间）
        String max = "a1" + "x".repeat(62);
        assertThatCode(() -> ValidationUtils.requireStrongPassword(max, "bob"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("空值：null 与空串都判为「密码不能为空」，不落进编码器变成 NPE")
    void shouldRejectEmpty() {
        assertThatThrownBy(() -> ValidationUtils.requireStrongPassword(null, "alice"))
                .isInstanceOf(BaseException.class).hasMessage("密码不能为空");
        assertThatThrownBy(() -> ValidationUtils.requireStrongPassword("", "alice"))
                .isInstanceOf(BaseException.class).hasMessage("密码不能为空");
    }

    @Test
    @DisplayName("长度：少于 8 位或多于 64 位都被拒，且提示带上下界")
    void shouldEnforceLength() {
        assertThatThrownBy(() -> ValidationUtils.requireStrongPassword("a123456", "alice"))
                .isInstanceOf(BaseException.class).hasMessageContaining("不能少于 8 位");

        String tooLong = "a1" + "x".repeat(63);   // 65 位
        assertThatThrownBy(() -> ValidationUtils.requireStrongPassword(tooLong, "alice"))
                .isInstanceOf(BaseException.class).hasMessageContaining("不能超过 64 位");
    }

    @Test
    @DisplayName("空白处理：只拒绝**首尾**空白；中间空格放行（它对密码熵有益，且不该被静默改写）")
    void shouldRejectSurroundingWhitespaceButAllowInner() {
        // 首尾空白：拒绝，且不做静默 trim —— 密码参与哈希，改写会让用户"输对了也登不上"
        assertThatThrownBy(() -> ValidationUtils.requireStrongPassword(" abcd1234", "alice"))
                .isInstanceOf(BaseException.class).hasMessageContaining("首尾不能包含空格");
        assertThatThrownBy(() -> ValidationUtils.requireStrongPassword("abcd1234 ", "alice"))
                .isInstanceOf(BaseException.class).hasMessageContaining("首尾不能包含空格");
        assertThatThrownBy(() -> ValidationUtils.requireStrongPassword("\tabcd1234", "alice"))
                .isInstanceOf(BaseException.class).hasMessageContaining("首尾不能包含空格");

        // 中间空格：应通过（"abcd 1234" 有字母有数字、长度够、不在黑名单）
        assertThatCode(() -> ValidationUtils.requireStrongPassword("abcd 1234", "alice"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678", "abcdefgh", "ABCDEFGH"})
    @DisplayName("字符类：纯数字或纯字母都被拒（必须同时含字母与数字）")
    void shouldRequireBothLetterAndDigit(String password) {
        assertThatThrownBy(() -> ValidationUtils.requireStrongPassword(password, "alice"))
                .isInstanceOf(BaseException.class).hasMessageContaining("必须同时包含字母和数字");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "abc12345",      // 黑名单 —— 能过"长度 8 + 字母 + 数字"，只能靠黑名单拦
            "abc123456",     // 9 位变体也在名单里
            "qwerty123",     // 9 位
            "Passw0rd"       // 大小写混合：证明黑名单比较是**大小写不敏感**的（小写命中 passw0rd）
    })
    @DisplayName("弱口令黑名单：能过长度与字符类规则，但仍在名单内 → 拒绝（大小写不敏感）")
    void shouldRejectWeakPasswords(String password) {
        assertThatThrownBy(() -> ValidationUtils.requireStrongPassword(password, "alice"))
                .isInstanceOf(BaseException.class).hasMessageContaining("过于常见");
    }

    @Test
    @DisplayName("风格相近但不在黑名单的密码应通过（避免黑名单写得太宽把正常密码也拦了）")
    void shouldNotRejectNonBlacklistedPassword() {
        assertThatCode(() -> ValidationUtils.requireStrongPassword("alice2024", "bob"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ValidationUtils.requireStrongPassword("xyz98765", "bob"))
                .doesNotThrowAnyException();
        // 与 "Passw0rd" 同构、换掉核心词
        assertThatCode(() -> ValidationUtils.requireStrongPassword("Nimda2024", "bob"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("规则优先级：长度不足时先报长度（即使它同时也在黑名单里，如 a123456）")
    void lengthRuleShouldTakePrecedence() {
        assertThatThrownBy(() -> ValidationUtils.requireStrongPassword("a123456", "alice"))
                .isInstanceOf(BaseException.class).hasMessageContaining("不能少于 8 位");
    }

    @Test
    @DisplayName("用户名即密码：大小写不敏感地拒绝")
    void shouldRejectPasswordEqualToUsername() {
        assertThatThrownBy(() -> ValidationUtils.requireStrongPassword("alice123", "alice123"))
                .isInstanceOf(BaseException.class).hasMessageContaining("不能与用户名相同");
        // 大小写不同也算相同
        assertThatThrownBy(() -> ValidationUtils.requireStrongPassword("Alice123", "alice123"))
                .isInstanceOf(BaseException.class).hasMessageContaining("不能与用户名相同");
        // 用户名首尾带空白时按 trim 后比较（用户名入库前已 trim）
        assertThatThrownBy(() -> ValidationUtils.requireStrongPassword("alice123", "  alice123  "))
                .isInstanceOf(BaseException.class).hasMessageContaining("不能与用户名相同");
    }

    @Test
    @DisplayName("username 为 null（注册前置校验等场景）时跳过「与用户名相同」判断，其余规则照旧")
    void shouldSkipUsernameRuleWhenNull() {
        assertThatCode(() -> ValidationUtils.requireStrongPassword("alice123", null))
                .doesNotThrowAnyException();
        // 但长度/字符类规则仍然生效
        assertThatThrownBy(() -> ValidationUtils.requireStrongPassword("12345678", null))
                .isInstanceOf(BaseException.class);
    }

    @Test
    @DisplayName("演示口令 123456 本身会被拒（证明规则确实生效），但它**不影响已有账号登录**")
    void demoPasswordShouldBeRejectedButLoginUnaffected() {
        // 规则层面：6 位且纯数字 → 拒绝
        assertThatThrownBy(() -> ValidationUtils.requireStrongPassword("123456", "admin"))
                .isInstanceOf(BaseException.class);

        // 说明：登录路径不调用本方法，因此库中已存在的 123456 账号仍可登录。
        // 这一点由 UserServiceImplLoginTest 的“密码正确时正常登录”用例保证。
        assertThat(ValidationUtils.MIN_PASSWORD_LENGTH).isEqualTo(8);
    }
}
