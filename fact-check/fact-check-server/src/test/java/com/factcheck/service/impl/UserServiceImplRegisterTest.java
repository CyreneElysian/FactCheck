package com.factcheck.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.factcheck.dto.UserRegisterDTO;
import com.factcheck.entity.User;
import com.factcheck.exception.AccountExistsException;
import com.factcheck.exception.BaseException;
import com.factcheck.mapper.UserMapper;
import com.factcheck.properties.WeChatProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 用户注册的参数校验测试（P2-17）
 *
 * <p>修复前的真实缺陷：{@code register} **完全没有校验**——
 * 密码为 null 会直接在 {@code passwordEncoder.encode(null)} 抛 NPE，
 * 前端只能看到"未知错误"；超长用户名/昵称则要等到写库才报数据完整性错误。</p>
 *
 * <p>完全离线：Mapper 全部 mock，BCrypt 用真实实现（纯 CPU）。</p>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplRegisterTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private WeChatProperties weChatProperties;

    @InjectMocks
    private UserServiceImpl userService;

    private UserRegisterDTO dto(String username, String password, String nickname) {
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        dto.setNickname(nickname);
        return dto;
    }

    @Test
    @DisplayName("密码为 null → 明确的业务提示，而不是 NPE（修复前会 NPE 变成“未知错误”）")
    void shouldRejectNullPasswordWithoutNpe() {
        assertThatThrownBy(() -> userService.register(dto("alice", null, null)))
                .isInstanceOf(BaseException.class)
                .hasMessage("密码不能为空");

        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    @DisplayName("用户名为纯空白 → 拒绝（原先没有任何校验，会把空白用户名写进库）")
    void shouldRejectBlankUsername() {
        assertThatThrownBy(() -> userService.register(dto("   ", "secret123", null)))
                .isInstanceOf(BaseException.class)
                .hasMessage("用户名不能为空");

        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    @DisplayName("用户名超长（列宽 50）→ 在写库前拒绝")
    void shouldRejectTooLongUsername() {
        assertThatThrownBy(() -> userService.register(dto("a".repeat(51), "secret123", null)))
                .isInstanceOf(BaseException.class)
                .hasMessage("用户名长度不能超过 50 个字符");
    }

    @Test
    @DisplayName("昵称超长（列宽 50）→ 拒绝")
    void shouldRejectTooLongNickname() {
        assertThatThrownBy(() -> userService.register(dto("alice", "secret123", "n".repeat(51))))
                .isInstanceOf(BaseException.class)
                .hasMessage("昵称长度不能超过 50 个字符");
    }

    @Test
    @DisplayName("合法注册：用户名/昵称去空白后落库，密码以 BCrypt 存储（不落明文）")
    void shouldRegisterWithTrimmedValues() {
        when(userMapper.getByUsername("alice")).thenReturn(null);

        userService.register(dto("  alice  ", "secret123", "  小明  "));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getUsername()).isEqualTo("alice");
        assertThat(saved.getNickname()).isEqualTo("小明");
        assertThat(saved.getPassword()).isNotEqualTo("secret123").startsWith("$2a$");
        assertThat(saved.getRole()).isEqualTo(0);
    }

    @Test
    @DisplayName("昵称留空时回退为用户名（保持原有行为）")
    void shouldFallbackNicknameToUsername() {
        when(userMapper.getByUsername("alice")).thenReturn(null);

        userService.register(dto("alice", "secret123", "   "));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertThat(captor.getValue().getNickname()).isEqualTo("alice");
    }

    @Test
    @DisplayName("用户名已存在 → AccountExistsException（校验通过后才查重）")
    void shouldRejectExistingUsername() {
        when(userMapper.getByUsername("alice")).thenReturn(User.builder().id(1L).build());

        assertThatThrownBy(() -> userService.register(dto("alice", "secret123", null)))
                .isInstanceOf(AccountExistsException.class);
    }
}
